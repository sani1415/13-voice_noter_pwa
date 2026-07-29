package com.voicenoter.assistant;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Low-latency Soniox live STT.
 * <p>
 * {@link #arm} pre-creates the mic + API key (no billing). {@link #start} begins
 * capture immediately and connects the WebSocket in parallel. {@link #stop} returns
 * control instantly; EOF/close runs in the background.
 */
public class SonioxLiveTranscriber {
    private static final String WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket";
    private static final int SAMPLE_RATE = 16000;
    /** ~3 seconds of mono PCM at 16 kHz before dropping oldest chunks. */
    private static final int MAX_BUFFER_BYTES = 96000;
    /** Keep capturing briefly after stop so trailing speech is not cut. */
    private static final long STOP_TAIL_MS = 180L;
    /** Wait for Soniox final tokens after EOF (UI already idle). */
    private static final long STOP_FINALIZE_MS = 700L;

    private static final OkHttpClient SHARED_HTTP = new OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();

    public interface Listener {
        void onConnecting();
        void onListening();
        void onTranscriptUpdate(String finalText, String partialText);
        void onError(String message);
        void onStopped();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean stopNotified = new AtomicBoolean(false);
    private final AtomicBoolean listeningNotified = new AtomicBoolean(false);
    private final AtomicBoolean wsReady = new AtomicBoolean(false);
    private final StringBuilder finalText = new StringBuilder();
    private final ArrayDeque<byte[]> audioBuffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    private final Object armLock = new Object();
    private String partialText = "";

    private Context appContext;
    private String baseUrl;
    private String language;
    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private Listener listener;
    private int bufferedBytes = 0;
    private int audioMinBuffer = 0;
    private boolean micArmed = false;
    private volatile CountDownLatch finishedLatch = new CountDownLatch(1);

    /** Prefetch API key and prepare AudioRecord (no WebSocket — no billing). */
    public void arm(Context context, String baseUrl, String language) {
        Context app = context.getApplicationContext();
        this.appContext = app;
        this.baseUrl = baseUrl;
        this.language = language;
        executor.execute(() -> {
            try {
                if (SonioxKeyCache.getValidKey(app) == null) {
                    SonioxKeyCache.fetchAndCache(app, baseUrl);
                }
            } catch (Exception ignored) {
            }
            synchronized (armLock) {
                if (running.get() || capturing.get()) return;
                if (audioRecord != null
                    && audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    micArmed = true;
                    return;
                }
                releaseMicLocked();
                AudioRecord record = createAudioRecord();
                if (record != null) {
                    audioRecord = record;
                    micArmed = true;
                } else {
                    micArmed = false;
                }
            }
        });
    }

    public boolean isArmed() {
        synchronized (armLock) {
            return micArmed
                && audioRecord != null
                && audioRecord.getState() == AudioRecord.STATE_INITIALIZED
                && !running.get();
        }
    }

    public void start(Context context, String baseUrl, String language, Listener listener) {
        if (running.getAndSet(true)) return;
        stopNotified.set(false);
        listeningNotified.set(false);
        wsReady.set(false);
        capturing.set(false);
        stopping.set(false);
        finishedLatch = new CountDownLatch(1);
        this.appContext = context.getApplicationContext();
        this.baseUrl = baseUrl;
        this.language = language;
        this.listener = listener;
        finalText.setLength(0);
        partialText = "";
        synchronized (bufferLock) {
            audioBuffer.clear();
            bufferedBytes = 0;
        }

        // Start mic on this call path ASAP — do not wait for key/WS.
        AudioRecord record;
        synchronized (armLock) {
            record = audioRecord;
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseMicLocked();
                record = createAudioRecord();
                audioRecord = record;
            }
            micArmed = false;
        }

        if (record == null) {
            fail("Mic unavailable");
            return;
        }

        notifyConnecting();
        startEarlyAudioCapture(record);

        executor.execute(() -> {
            try {
                String apiKey = SonioxKeyCache.getValidKey(appContext);
                if (apiKey == null || apiKey.isEmpty()) {
                    apiKey = SonioxKeyCache.fetchAndCache(appContext, baseUrl);
                }
                if (!running.get() || stopping.get()) return;
                if (apiKey == null || apiKey.isEmpty()) {
                    fail("Soniox key failed");
                    return;
                }
                connectWebSocket(apiKey, language);
            } catch (Exception e) {
                if (running.get() && !stopping.get()) {
                    fail(e.getMessage() != null ? e.getMessage() : "Live start failed");
                }
            }
        });
    }

    /**
     * Soft stop: mic UI can go idle immediately (caller), while this keeps a short
     * audio tail + final token window so trailing words still land via
     * {@link Listener#onTranscriptUpdate}. {@link Listener#onStopped} fires when done.
     */
    public void stop() {
        if (!running.get()) return;
        if (!stopping.compareAndSet(false, true)) return;
        executor.execute(this::finalizeClose);
    }

    /** Immediate teardown (destroy / quiet cancel). */
    public void cancel() {
        stopping.set(true);
        capturing.set(false);
        running.set(false);
        wsReady.set(false);
        finishedLatch.countDown();
        Listener old = listener;
        listener = null;
        stopAudioCaptureKeepRecord(true);
        cancelWebSocket();
        if (old != null && stopNotified.compareAndSet(false, true)) {
            mainHandler.post(old::onStopped);
        }
    }

    /** Release armed mic when leaving live mode / destroying controller. */
    public void releaseArm() {
        synchronized (armLock) {
            if (running.get() || capturing.get()) return;
            releaseMicLocked();
            micArmed = false;
        }
    }

    private void finalizeClose() {
        // Brief tail capture so the last syllables are not cut at button-up.
        try {
            Thread.sleep(STOP_TAIL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        capturing.set(false);
        stopAudioCaptureKeepRecord(false);

        WebSocket socket = webSocket;
        if (socket != null && wsReady.get()) {
            flushBufferedAudio(socket);
            try {
                socket.send(ByteString.EMPTY);
            } catch (Exception ignored) {
            }
            try {
                finishedLatch.await(STOP_FINALIZE_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        running.set(false);
        wsReady.set(false);
        webSocket = null;
        if (socket != null) {
            try {
                socket.close(1000, "stop");
            } catch (Exception ignored) {
            }
        }

        notifyStoppedOnce();

        synchronized (armLock) {
            // A newer start() clears stopping; do not touch its mic.
            if (!stopping.get() || capturing.get() || running.get()) return;
            if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseMicLocked();
                AudioRecord next = createAudioRecord();
                if (next != null) {
                    audioRecord = next;
                    micArmed = true;
                }
            } else {
                micArmed = true;
            }
        }
    }

    private AudioRecord createAudioRecord() {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        audioMinBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
        if (audioMinBuffer <= 0) return null;

        // VOICE_RECOGNITION: lower-latency path tuned for speech on most devices.
        AudioRecord record = new AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            channelConfig,
            encoding,
            Math.max(audioMinBuffer, 512) * 2
        );
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            // Fallback for devices that reject VOICE_RECOGNITION.
            record = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                encoding,
                Math.max(audioMinBuffer, 512) * 2
            );
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                try {
                    record.release();
                } catch (Exception ignored) {
                }
                return null;
            }
        }
        return record;
    }

    private void connectWebSocket(String apiKey, String language) {
        Request request = new Request.Builder().url(WS_URL).build();
        webSocket = SHARED_HTTP.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                if (!running.get() || stopping.get()) return;
                try {
                    JSONObject config = new JSONObject();
                    config.put("api_key", apiKey);
                    config.put("model", "stt-rt-v5");
                    config.put("audio_format", "pcm_s16le");
                    config.put("sample_rate", SAMPLE_RATE);
                    config.put("num_channels", 1);
                    config.put("language_hints", new JSONArray(new String[]{languageHint(language)}));
                    config.put("language_hints_strict", true);
                    config.put("enable_endpoint_detection", true);
                    socket.send(config.toString());
                    wsReady.set(true);
                    flushBufferedAudio(socket);
                } catch (Exception e) {
                    fail(e.getMessage() != null ? e.getMessage() : "Config failed");
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                finishedLatch.countDown();
                if (running.get() && !stopping.get()) {
                    fail(t.getMessage() != null ? t.getMessage() : "WebSocket failed");
                } else if (stopping.get()) {
                    notifyStoppedOnce();
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                finishedLatch.countDown();
            }
        });
    }

    private void startEarlyAudioCapture(AudioRecord record) {
        capturing.set(true);
        audioThread = new Thread(() -> {
            byte[] buffer = new byte[Math.max(audioMinBuffer, 512)];
            try {
                record.startRecording();
            } catch (Exception e) {
                fail(e.getMessage() != null ? e.getMessage() : "Mic start failed");
                return;
            }
            notifyListeningOnce();

            while (running.get() && capturing.get()) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                if (!running.get() || !capturing.get()) break;

                WebSocket socket = webSocket;
                if (wsReady.get() && socket != null) {
                    sendAudio(socket, buffer, read);
                } else {
                    enqueueAudio(buffer, read);
                }
            }
        }, "soniox-live-audio");
        audioThread.start();
    }

    private void sendAudio(WebSocket socket, byte[] buffer, int length) {
        if (!socket.send(ByteString.of(buffer, 0, length)) && running.get() && !stopping.get()) {
            fail("Audio send failed");
        }
    }

    private void enqueueAudio(byte[] buffer, int length) {
        byte[] chunk = new byte[length];
        System.arraycopy(buffer, 0, chunk, 0, length);
        synchronized (bufferLock) {
            while (bufferedBytes + length > MAX_BUFFER_BYTES && !audioBuffer.isEmpty()) {
                byte[] dropped = audioBuffer.pollFirst();
                if (dropped != null) bufferedBytes -= dropped.length;
            }
            audioBuffer.addLast(chunk);
            bufferedBytes += length;
        }
    }

    private void flushBufferedAudio(WebSocket socket) {
        synchronized (bufferLock) {
            while (!audioBuffer.isEmpty()) {
                byte[] chunk = audioBuffer.pollFirst();
                if (chunk == null) break;
                bufferedBytes -= chunk.length;
                if (!socket.send(ByteString.of(chunk))) return;
            }
        }
    }

    private void handleMessage(String text) {
        // Accept tokens while running, including the finalize window after stop.
        if (!running.get()) return;
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("error_code")) {
                if (stopping.get()) {
                    finishedLatch.countDown();
                    return;
                }
                fail(json.optString("error_message", "Soniox error"));
                return;
            }
            if (json.optBoolean("finished", false)) {
                finishedLatch.countDown();
                if (!stopping.get()) {
                    running.set(false);
                    capturing.set(false);
                    notifyStoppedOnce();
                }
                return;
            }

            JSONArray tokens = json.optJSONArray("tokens");
            if (tokens == null) return;

            String partial = "";
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject token = tokens.getJSONObject(i);
                String translationStatus = token.optString("translation_status", "");
                if (!translationStatus.isEmpty()
                    && !"none".equals(translationStatus)
                    && !"original".equals(translationStatus)) {
                    continue;
                }
                String tokenText = token.optString("text", "");
                if (!isDisplayableToken(tokenText)) continue;
                if (token.optBoolean("is_final", false)) {
                    finalText.append(tokenText);
                } else {
                    partial += tokenText;
                }
            }
            partialText = partial;
            notifyUpdate();
        } catch (Exception e) {
            if (!stopping.get()) {
                fail("Parse error");
            }
        }
    }

    private boolean isDisplayableToken(String tokenText) {
        if (tokenText == null || tokenText.isEmpty()) return false;
        if ("<end>".equals(tokenText)) return false;
        return tokenText.length() < 2
            || tokenText.charAt(0) != '<'
            || tokenText.charAt(tokenText.length() - 1) != '>';
    }

    private void notifyConnecting() {
        mainHandler.post(() -> {
            if (listener != null && running.get() && !stopping.get()) {
                listener.onConnecting();
            }
        });
    }

    private void notifyListeningOnce() {
        if (!listeningNotified.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            if (listener != null && running.get() && !stopping.get()) {
                listener.onListening();
            }
        });
    }

    private void notifyUpdate() {
        mainHandler.post(() -> {
            // Keep pushing tokens during finalize so trailing words reach the field.
            if (listener != null && running.get()) {
                listener.onTranscriptUpdate(finalText.toString(), partialText);
            }
        });
    }

    /** Stop capture; optionally release the AudioRecord. */
    private void stopAudioCaptureKeepRecord(boolean release) {
        capturing.set(false);
        AudioRecord record;
        synchronized (armLock) {
            record = audioRecord;
            if (release) {
                audioRecord = null;
                micArmed = false;
            }
        }
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Exception ignored) {
            }
            if (release) {
                try {
                    record.release();
                } catch (Exception ignored) {
                }
            }
        }
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void releaseMicLocked() {
        AudioRecord record = audioRecord;
        audioRecord = null;
        micArmed = false;
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                record.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelWebSocket() {
        if (webSocket != null) {
            try {
                webSocket.cancel();
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
    }

    private void fail(String message) {
        if (stopping.get()) return;
        if (!running.getAndSet(false)) return;
        capturing.set(false);
        wsReady.set(false);
        stopAudioCaptureKeepRecord(true);
        cancelWebSocket();
        mainHandler.post(() -> {
            if (listener != null) {
                Listener current = listener;
                listener = null;
                current.onError(message);
                if (stopNotified.compareAndSet(false, true)) {
                    current.onStopped();
                }
            }
        });
    }

    private void notifyStoppedOnce() {
        mainHandler.post(() -> {
            if (!stopNotified.compareAndSet(false, true)) return;
            if (listener != null) {
                Listener current = listener;
                listener = null;
                current.onStopped();
            }
        });
    }

    private String languageHint(String language) {
        if (MainActivity.LANG_EN.equals(language)) return "en";
        if (MainActivity.LANG_AR.equals(language)) return "ar";
        return "bn";
    }
}
