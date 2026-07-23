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
    private SonioxLiveTranscriber liveTranscriber;
    private boolean isRecording;
    private boolean isTranscribing;
    private boolean isLiveActive;
    private boolean isLiveConnecting;
    private int voiceSessionGeneration;
    private String liveFinalText = "";
    private boolean liveInsertedViaA11y;

    VoiceSessionController(Context context, UiCallback ui) {
        this.appContext = context.getApplicationContext();
        this.ui = ui;
    }

    boolean isBusy() {
        return isRecording || isTranscribing || isLiveActive;
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
        prefetchSonioxIfNeeded();
    }

    void toggleVoice() {
        if (isLiveMode()) {
            if (isLiveActive) stopLive(true);
            else startLive();
        } else if (isRecording) {
            stopAndTranscribe();
        } else if (!isTranscribing) {
            startRecording();
        }
    }

    void stopQuietly() {
        voiceSessionGeneration++;
        if (isLiveActive || liveTranscriber != null) {
            stopLive(false);
        }
        if (isRecording) {
            cancelRecordingQuietly();
        }
        isTranscribing = false;
        ui.onIdle(isLiveMode());
    }

    void destroy() {
        stopQuietly();
        executor.shutdownNow();
    }

    void prefetchSonioxIfNeeded() {
        if (!isLiveMode()) return;
        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) return;
        SonioxKeyCache.prefetch(appContext, endpoint, executor);
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
            ui.onRecording();
        } catch (Exception e) {
            cancelRecordingQuietly();
            ui.onStatus("Mic error");
        }
    }

    private void stopAndTranscribe() {
        if (!isRecording) return;
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
        if (isLiveActive || isRecording || isTranscribing) return;
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
        liveFinalText = "";
        liveInsertedViaA11y = false;
        final int session = ++voiceSessionGeneration;
        ui.onLiveConnecting();
        TextInsertAccessibilityService.beginLive();

        liveTranscriber = new SonioxLiveTranscriber();
        liveTranscriber.start(appContext, endpoint, currentLanguage(), new SonioxLiveTranscriber.Listener() {
            @Override
            public void onConnecting() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = true;
                    ui.onLiveConnecting();
                });
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
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    if (isLiveConnecting) {
                        isLiveConnecting = false;
                        ui.onLiveListening();
                    }
                    liveFinalText = VoicePunctuation.apply(finalText != null ? finalText : "");
                    String display = liveFinalText + (partialText != null ? partialText : "");
                    if (TextInsertAccessibilityService.updateLive(display)) {
                        liveInsertedViaA11y = true;
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (session != voiceSessionGeneration) return;
                mainHandler.post(() -> {
                    isLiveConnecting = false;
                    isLiveActive = false;
                    liveTranscriber = null;
                    TextInsertAccessibilityService.endLive();
                    liveInsertedViaA11y = false;
                    ui.onStatus(message != null && !message.isEmpty() ? message : "Live error");
                    ui.onIdle(isLiveMode());
                });
            }

            @Override
            public void onStopped() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration) return;
                    isLiveConnecting = false;
                    isLiveActive = false;
                    liveTranscriber = null;
                    TextInsertAccessibilityService.endLive();
                    ui.onIdle(isLiveMode());
                });
            }
        });
    }

    private void stopLive(boolean deliver) {
        isLiveActive = false;
        isLiveConnecting = false;
        SonioxLiveTranscriber active = liveTranscriber;
        liveTranscriber = null;
        String text = liveFinalText;
        liveFinalText = "";
        boolean alreadyInserted = liveInsertedViaA11y;
        liveInsertedViaA11y = false;
        TextInsertAccessibilityService.endLive();
        if (active != null) active.stop();
        if (deliver && text != null && !text.trim().isEmpty()) {
            if (alreadyInserted) {
                // Live text already written into the focused field.
                clipboardBackup(text.trim());
                ui.onStatus("Inserted");
            } else {
                deliverResult(text.trim());
            }
        }
        ui.onIdle(isLiveMode());
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
