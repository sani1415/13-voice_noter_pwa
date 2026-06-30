package com.voicenoter.keyboard;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class SonioxLiveTranscriber {
    private static final String WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket";
    private static final int SAMPLE_RATE = 16000;
    /** ~3 seconds of mono PCM at 16 kHz before dropping oldest chunks. */
    private static final int MAX_BUFFER_BYTES = 96000;

    public interface Listener {
        void onConnecting();
        void onListening();
        void onTranscriptUpdate(String finalText, String partialText);
        void onError(String message);
        void onStopped();
    }

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopNotified = new AtomicBoolean(false);
    private final AtomicBoolean listeningNotified = new AtomicBoolean(false);
    private final AtomicBoolean wsReady = new AtomicBoolean(false);
    private final StringBuilder finalText = new StringBuilder();
    private final ArrayDeque<byte[]> audioBuffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    private String partialText = "";

    private Context appContext;
    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private Listener listener;
    private int bufferedBytes = 0;
    private int audioMinBuffer = 0;

    public void start(Context context, String baseUrl, String language, Listener listener) {
        if (running.getAndSet(true)) return;
        stopNotified.set(false);
        listeningNotified.set(false);
        wsReady.set(false);
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        finalText.setLength(0);
        partialText = "";
        synchronized (bufferLock) {
            audioBuffer.clear();
            bufferedBytes = 0;
        }

        notifyConnecting();

        new Thread(() -> {
            try {
                AtomicReference<String> apiKeyRef = new AtomicReference<>(
                    SonioxKeyCache.getValidKey(appContext)
                );
                AtomicReference<AudioRecord> recordRef = new AtomicReference<>();
                CountDownLatch parallel = new CountDownLatch(2);

                Thread keyThread = new Thread(() -> {
                    try {
                        if (apiKeyRef.get() == null) {
                            apiKeyRef.set(SonioxKeyCache.fetchAndCache(appContext, baseUrl));
                        }
                    } catch (Exception e) {
                        apiKeyRef.set(null);
                    } finally {
                        parallel.countDown();
                    }
                }, "soniox-key-fetch");

                Thread micThread = new Thread(() -> {
                    try {
                        recordRef.set(prepareAudioRecord());
                    } finally {
                        parallel.countDown();
                    }
                }, "soniox-mic-prep");

                keyThread.start();
                micThread.start();
                parallel.await();

                String apiKey = apiKeyRef.get();
                AudioRecord record = recordRef.get();
                if (apiKey == null || apiKey.isEmpty()) {
                    fail("Soniox key failed");
                    return;
                }
                if (record == null) {
                    fail("Mic unavailable");
                    return;
                }

                audioRecord = record;
                startEarlyAudioCapture(record);
                connectWebSocket(apiKey, language);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Live start interrupted");
            } catch (Exception e) {
                fail(e.getMessage() != null ? e.getMessage() : "Live start failed");
            }
        }, "soniox-live-start").start();
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        wsReady.set(false);
        listener = null;
        stopAudioCapture();
        executor.execute(this::closeWebSocketAndNotify);
    }

    private AudioRecord prepareAudioRecord() {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        audioMinBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
        if (audioMinBuffer <= 0) return null;

        AudioRecord record = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            channelConfig,
            encoding,
            audioMinBuffer * 2
        );
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            return null;
        }
        return record;
    }

    private void connectWebSocket(String apiKey, String language) {
        Request request = new Request.Builder().url(WS_URL).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
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
                if (running.get()) {
                    fail(t.getMessage() != null ? t.getMessage() : "WebSocket failed");
                } else {
                    notifyStoppedOnce();
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                notifyStoppedOnce();
            }
        });
    }

    private void startEarlyAudioCapture(AudioRecord record) {
        audioThread = new Thread(() -> {
            byte[] buffer = new byte[Math.max(audioMinBuffer, 512)];
            record.startRecording();
            notifyListeningOnce();

            while (running.get()) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                if (!running.get()) break;

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
        if (!socket.send(ByteString.of(buffer, 0, length)) && running.get()) {
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
            while (!audioBuffer.isEmpty() && running.get()) {
                byte[] chunk = audioBuffer.pollFirst();
                if (chunk == null) break;
                bufferedBytes -= chunk.length;
                if (!socket.send(ByteString.of(chunk))) {
                    if (running.get()) {
                        fail("Audio send failed");
                    }
                    return;
                }
            }
        }
    }

    private void handleMessage(String text) {
        if (!running.get()) return;
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("error_code")) {
                fail(json.optString("error_message", "Soniox error"));
                return;
            }
            if (json.optBoolean("finished", false)) {
                running.set(false);
                stopAudioCapture();
                notifyStoppedOnce();
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
            fail("Parse error");
        }
    }

    private boolean isDisplayableToken(String tokenText) {
        if (tokenText == null || tokenText.isEmpty()) return false;
        if ("<end>".equals(tokenText)) return false;
        return tokenText.length() < 2 || tokenText.charAt(0) != '<' || tokenText.charAt(tokenText.length() - 1) != '>';
    }

    private void notifyConnecting() {
        mainHandler.post(() -> {
            if (listener != null && running.get()) {
                listener.onConnecting();
            }
        });
    }

    private void notifyListeningOnce() {
        if (!listeningNotified.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            if (listener != null && running.get()) {
                listener.onListening();
            }
        });
    }

    private void notifyUpdate() {
        mainHandler.post(() -> {
            if (listener != null && running.get()) {
                listener.onTranscriptUpdate(finalText.toString(), partialText);
            }
        });
    }

    private void closeWebSocketAndNotify() {
        if (webSocket != null) {
            try {
                webSocket.send(ByteString.EMPTY);
            } catch (Exception ignored) {
            }
            try {
                webSocket.close(1000, "stop");
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
        notifyStoppedOnce();
    }

    private synchronized void stopAudioCapture() {
        AudioRecord record = audioRecord;
        audioRecord = null;
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
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void fail(String message) {
        if (!running.getAndSet(false)) return;
        wsReady.set(false);
        stopAudioCapture();
        if (webSocket != null) {
            try {
                webSocket.cancel();
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(message);
                notifyStoppedOnceOnMain();
            }
        });
    }

    private void notifyStoppedOnce() {
        mainHandler.post(this::notifyStoppedOnceOnMain);
    }

    private void notifyStoppedOnceOnMain() {
        if (!stopNotified.compareAndSet(false, true)) return;
        if (listener != null) {
            Listener current = listener;
            listener = null;
            current.onStopped();
        }
    }

    private String languageHint(String language) {
        if (MainActivity.LANG_EN.equals(language)) return "en";
        if (MainActivity.LANG_AR.equals(language)) return "ar";
        return "bn";
    }
}
