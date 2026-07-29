package com.voicenoter.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Record → /api/transcribe and Live (Soniox) sessions for the floating bubble.
 * Finished text is copied to the clipboard (v1 — no accessibility insert yet).
 */
final class VoiceSessionController {
    private static final String MIME_TYPE = "audio/mp4";

    interface UiCallback {
        void onIdle(boolean liveMode);
        void onRecording();
        void onTranscribing();
        void onLiveConnecting();
        void onLiveListening();
        void onStatus(String message);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final UiCallback ui;

    private MediaRecorder recorder;
    private File audioFile;
    private final SonioxLiveTranscriber liveEngine = new SonioxLiveTranscriber();
    private boolean isRecording;
    private boolean isTranscribing;
    private boolean isLiveActive;
    private boolean isLiveConnecting;
    private boolean isLiveFinalizing;
    private boolean liveStopDeliver;
    private int voiceSessionGeneration;
    private String liveFinalText = "";
    private String livePartialText = "";
    private boolean liveInsertedViaA11y;
    private final Runnable sessionLimitRunnable;

    VoiceSessionController(Context context, UiCallback ui) {
        this.appContext = context.getApplicationContext();
        this.ui = ui;
        this.sessionLimitRunnable = () -> {
            if (isRecording) {
                this.ui.onStatus("Time limit — stopping");
                stopAndTranscribe();
            } else if (isLiveActive) {
                this.ui.onStatus("Time limit — stopping");
                stopLive(true);
            }
        };
    }

    boolean isBusy() {
        return isRecording || isTranscribing || isLiveActive || isLiveFinalizing;
    }

    boolean isLiveMode() {
        return MainActivity.MODE_LIVE.equals(currentMode());
    }

    void toggleMode() {
        SharedPreferences prefs = prefs();
        String next = MainActivity.MODE_LIVE.equals(currentMode())
            ? MainActivity.MODE_RECORD
            : MainActivity.MODE_LIVE;
        prefs.edit().putString(MainActivity.KEY_VOICE_INPUT_MODE, next).apply();
        if (!isBusy()) {
            ui.onIdle(MainActivity.MODE_LIVE.equals(next));
        }
        Toast.makeText(appContext,
            MainActivity.MODE_LIVE.equals(next) ? "Live transcription" : "Record then transcribe",
            Toast.LENGTH_SHORT).show();
        if (MainActivity.MODE_LIVE.equals(next)) {
            armLiveEngine();
        } else {
            liveEngine.releaseArm();
        }
    }

    void toggleVoice() {
        if (isLiveMode()) {
            if (isLiveFinalizing) return;
            if (isLiveActive) stopLive(true);
            else startLive();
        } else if (isRecording) {
            stopAndTranscribe();
        } else if (!isTranscribing) {
            startRecording();
        }
    }

    /** Start live on touch-down so WS/mic overlap the finger press. */
    boolean startLiveIfIdle() {
        if (!isLiveMode()) return false;
        if (isLiveFinalizing) {
            // New tap wins — drop the trailing finalize of the previous utterance.
            forceStopLive();
        }
        if (isBusy()) return false;
        startLive();
        return isLiveActive;
    }

    void stopQuietly() {
        voiceSessionGeneration++;
        if (isLiveActive || isLiveFinalizing) {
            forceStopLive();
        }
        if (isRecording) {
            cancelRecordingQuietly();
        }
        isTranscribing = false;
        ui.onIdle(isLiveMode());
    }

    void destroy() {
        cancelSessionLimit();
        stopQuietly();
        liveEngine.releaseArm();
        executor.shutdownNow();
    }

    void prefetchSonioxIfNeeded() {
        armLiveEngine();
    }

    private void armLiveEngine() {
        if (!isLiveMode()) return;
        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) return;
        if (!hasMicPermission()) return;
        liveEngine.arm(appContext, endpoint, currentLanguage());
    }

    private void armSessionLimit() {
        cancelSessionLimit();
        int sec = prefs().getInt(
            MainActivity.KEY_MAX_SESSION_SEC,
            MainActivity.DEFAULT_MAX_SESSION_SEC
        );
        if (sec <= 0) return;
        mainHandler.postDelayed(sessionLimitRunnable, sec * 1000L);
    }

    private void cancelSessionLimit() {
        mainHandler.removeCallbacks(sessionLimitRunnable);
    }

    private void startRecording() {
        if (!hasMicPermission()) {
            ui.onStatus("Grant mic in app");
            return;
        }
        if (getEndpoint().isEmpty()) {
            ui.onStatus("Set endpoint in app");
            return;
        }
        try {
            audioFile = File.createTempFile("voice-assistant-", ".m4a", appContext.getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            isRecording = true;
            voiceSessionGeneration++;
            armSessionLimit();
            ui.onRecording();
        } catch (Exception e) {
            cancelRecordingQuietly();
            ui.onStatus("Mic error");
        }
    }

    private void stopAndTranscribe() {
        if (!isRecording) return;
        cancelSessionLimit();
        final int session = voiceSessionGeneration;
        File finishedFile = audioFile;
        try {
            if (recorder != null) recorder.stop();
        } catch (RuntimeException e) {
            ui.onStatus("Too short");
            cancelRecordingQuietly();
            return;
        }
        stopRecorderQuietly();
        isTranscribing = true;
        ui.onTranscribing();
        executor.execute(() -> transcribe(finishedFile, session));
    }

    private void cancelRecordingQuietly() {
        cancelSessionLimit();
        voiceSessionGeneration++;
        isTranscribing = false;
        stopRecorderQuietly();
        if (audioFile != null) {
            audioFile.delete();
            audioFile = null;
        }
        ui.onIdle(isLiveMode());
    }

    private void stopRecorderQuietly() {
        isRecording = false;
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
        recorder = null;
    }

    private void transcribe(File file, int session) {
        try {
            if (session != voiceSessionGeneration) return;
            if (file == null || !file.exists() || file.length() == 0) {
                finishTranscribe(session, null, "No audio");
                return;
            }

            String endpoint = getEndpoint() + "/api/transcribe";
            String audio = encodeFile(file);
            JSONObject body = new JSONObject();
            body.put("audio", audio);
            body.put("mimeType", MIME_TYPE);
            body.put("language", currentLanguage());

            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
            ));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);

            if (code < 200 || code >= 300) {
                finishTranscribe(session, null, "Failed " + code);
                return;
            }

            String text = new JSONObject(response.toString()).optString("text", "").trim();
            if (text.isEmpty()) {
                finishTranscribe(session, null, "No speech");
                return;
            }
            finishTranscribe(session, text, null);
        } catch (Exception e) {
            finishTranscribe(session, null, "Error");
        } finally {
            if (file != null) file.delete();
        }
    }

    private void finishTranscribe(int session, String text, String error) {
        mainHandler.post(() -> {
            if (session != voiceSessionGeneration) return;
            isTranscribing = false;
            if (text != null && !text.isEmpty()) {
                deliverResult(VoicePunctuation.apply(text));
            } else if (error != null) {
                ui.onStatus(error);
            }
            ui.onIdle(isLiveMode());
        });
    }

    private void startLive() {
        if (isLiveActive || isLiveFinalizing || isRecording || isTranscribing) return;
        if (!hasMicPermission()) {
            ui.onStatus("Grant mic in app");
            return;
        }
        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) {
            ui.onStatus("Set endpoint in app");
            return;
        }

        isLiveActive = true;
        isLiveConnecting = true;
        isLiveFinalizing = false;
        liveStopDeliver = false;
        liveFinalText = "";
        livePartialText = "";
        liveInsertedViaA11y = false;
        final int session = ++voiceSessionGeneration;
        // Show listening immediately — mic starts in start(); WS catches up.
        ui.onLiveListening();
        armSessionLimit();
        TextInsertAccessibilityService.beginLive();

        liveEngine.start(appContext, endpoint, currentLanguage(), new SonioxLiveTranscriber.Listener() {
            @Override
            public void onConnecting() {
                // Mic-first path already shows listening; keep UI snappy.
            }

            @Override
            public void onListening() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = false;
                    ui.onLiveListening();
                });
            }

            @Override
            public void onTranscriptUpdate(String finalText, String partialText) {
                mainHandler.post(() -> {
                    // Keep writing during finalize so trailing words are not lost.
                    if (session != voiceSessionGeneration || (!isLiveActive && !isLiveFinalizing)) {
                        return;
                    }
                    isLiveConnecting = false;
                    liveFinalText = VoicePunctuation.apply(finalText != null ? finalText : "");
                    livePartialText = partialText != null ? partialText : "";
                    if (TextInsertAccessibilityService.updateLive(liveFinalText, livePartialText)) {
                        liveInsertedViaA11y = true;
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (session != voiceSessionGeneration) return;
                mainHandler.post(() -> {
                    if (!isLiveActive && !isLiveFinalizing) {
                        armLiveEngine();
                        return;
                    }
                    isLiveConnecting = false;
                    isLiveActive = false;
                    isLiveFinalizing = false;
                    liveStopDeliver = false;
                    liveFinalText = "";
                    livePartialText = "";
                    liveInsertedViaA11y = false;
                    cancelSessionLimit();
                    TextInsertAccessibilityService.endLive();
                    ui.onStatus(message != null && !message.isEmpty() ? message : "Live error");
                    ui.onIdle(isLiveMode());
                    armLiveEngine();
                });
            }

            @Override
            public void onStopped() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration) return;
                    completeLiveFinalize();
                });
            }
        });
        isLiveConnecting = false;
    }

    private void stopLive(boolean deliver) {
        if (!isLiveActive || isLiveFinalizing) return;
        cancelSessionLimit();
        isLiveActive = false;
        isLiveConnecting = false;
        isLiveFinalizing = true;
        liveStopDeliver = deliver;
        // Mic UI returns immediately; a11y stays open for trailing tokens.
        ui.onIdle(isLiveMode());
        liveEngine.stop();
    }

    private void completeLiveFinalize() {
        if (!isLiveFinalizing && !isLiveActive) {
            armLiveEngine();
            return;
        }
        boolean deliver = liveStopDeliver;
        boolean alreadyInserted = liveInsertedViaA11y;
        String text = (liveFinalText + livePartialText).trim();
        isLiveActive = false;
        isLiveConnecting = false;
        isLiveFinalizing = false;
        liveStopDeliver = false;
        liveFinalText = "";
        livePartialText = "";
        liveInsertedViaA11y = false;
        TextInsertAccessibilityService.endLive();

        if (deliver && !text.isEmpty()) {
            if (alreadyInserted) {
                clipboardBackup(text);
                ui.onStatus("Inserted");
            } else {
                deliverResult(text);
            }
        }
        ui.onIdle(isLiveMode());
        armLiveEngine();
    }

    private void forceStopLive() {
        cancelSessionLimit();
        isLiveConnecting = false;
        isLiveActive = false;
        isLiveFinalizing = false;
        liveStopDeliver = false;
        liveFinalText = "";
        livePartialText = "";
        liveInsertedViaA11y = false;
        TextInsertAccessibilityService.endLive();
        liveEngine.cancel();
        armLiveEngine();
    }

    /** Prefer Accessibility insert into focused field; clipboard is always kept as backup. */
    private void deliverResult(String text) {
        boolean inserted = TextInsertAccessibilityService.insertAtCursor(text);
        clipboardBackup(text);
        if (inserted) {
            Toast.makeText(appContext, "Inserted into text field", Toast.LENGTH_SHORT).show();
            ui.onStatus("Inserted");
        } else {
            String preview = text.length() > 80 ? text.substring(0, 80) + "…" : text;
            Toast.makeText(appContext,
                TextInsertAccessibilityService.isRunning()
                    ? "No text field focused — copied:\n" + preview
                    : "Enable Accessibility for auto-insert — copied:\n" + preview,
                Toast.LENGTH_LONG).show();
            ui.onStatus("Copied to clipboard");
        }
    }

    private void clipboardBackup(String text) {
        ClipboardManager clipboard =
            (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Voice Assistant", text));
        }
    }

    private String encodeFile(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    private String currentMode() {
        return prefs().getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
    }

    private String currentLanguage() {
        String lang = prefs().getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN);
        if (!MainActivity.LANG_BN.equals(lang)
            && !MainActivity.LANG_EN.equals(lang)
            && !MainActivity.LANG_AR.equals(lang)) {
            return MainActivity.LANG_BN;
        }
        return lang;
    }

    private String getEndpoint() {
        String endpoint = prefs().getString(MainActivity.KEY_ENDPOINT, MainActivity.DEFAULT_ENDPOINT);
        if (endpoint == null) return "";
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint.trim();
    }
}
