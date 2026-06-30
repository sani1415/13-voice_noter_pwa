package com.voicenoter.keyboard;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class SonioxLiveTranscriber {
    private static final String WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket";
    private static final int SAMPLE_RATE = 16000;

    public interface Listener {
        void onTranscriptUpdate(String finalText, String partialText);
        void onError(String message);
        void onStopped();
    }

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopNotified = new AtomicBoolean(false);
    private final StringBuilder finalText = new StringBuilder();
    private String partialText = "";

    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private Listener listener;

    public void start(String baseUrl, String language, Listener listener) {
        if (running.getAndSet(true)) return;
        stopNotified.set(false);
        this.listener = listener;
        finalText.setLength(0);
        partialText = "";

        executor.execute(() -> {
            try {
                String apiKey = fetchTemporaryApiKey(baseUrl);
                connectWebSocket(apiKey, language);
            } catch (Exception e) {
                fail(e.getMessage() != null ? e.getMessage() : "Live start failed");
            }
        });
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        listener = null;
        stopAudioCapture();
        executor.execute(this::closeWebSocketAndNotify);
    }

    private String fetchTemporaryApiKey(String baseUrl) throws Exception {
        String endpoint = baseUrl;
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "/api/soniox-key").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
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
            throw new Exception("Soniox key HTTP " + code);
        }

        JSONObject json = new JSONObject(response.toString());
        String apiKey = json.optString("api_key", "").trim();
        if (apiKey.isEmpty()) {
            throw new Exception("Missing Soniox API key");
        }
        return apiKey;
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
                    startAudioCapture(socket);
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

    private void startAudioCapture(WebSocket socket) {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
        if (minBuffer <= 0) {
            fail("Mic unavailable");
            return;
        }

        AudioRecord record = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            channelConfig,
            encoding,
            minBuffer * 2
        );
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            fail("Mic init failed");
            return;
        }

        audioRecord = record;
        audioThread = new Thread(() -> {
            record.startRecording();
            byte[] buffer = new byte[minBuffer];
            while (running.get()) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                if (!running.get()) break;
                if (!socket.send(ByteString.of(buffer, 0, read))) {
                    if (running.get()) {
                        fail("Audio send failed");
                    }
                    return;
                }
            }
        }, "soniox-live-audio");
        audioThread.start();
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
