package com.voicenoter.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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

public class VoiceKeyboardService extends InputMethodService {
    private static final String MIME_TYPE = "audio/mp4";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable repeatDeleteRunnable = new Runnable() {
        @Override
        public void run() {
            deleteBackward();
            mainHandler.postDelayed(this, 55);
        }
    };
    private MediaRecorder recorder;
    private File audioFile;
    private TextView status;
    private Button micButton;
    private volatile boolean isRecording = false;

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackgroundColor(0xfff7f3ec);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        micButton = new Button(this);
        micButton.setText("Start voice");
        micButton.setAllCaps(false);
        micButton.setOnClickListener(v -> toggleRecording());
        row.addView(micButton, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button space = new Button(this);
        space.setText("Space");
        space.setAllCaps(false);
        space.setOnClickListener(v -> commitText(" "));
        row.addView(space, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button backspace = new Button(this);
        backspace.setText("Delete");
        backspace.setAllCaps(false);
        configureDeleteButton(backspace);
        row.addView(backspace, new LinearLayout.LayoutParams(0, dp(52), 1));

        root.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER);
        utilityRow.setPadding(0, dp(6), 0, 0);

        Button comma = new Button(this);
        comma.setText(",");
        comma.setAllCaps(false);
        comma.setOnClickListener(v -> commitText(", "));
        utilityRow.addView(comma, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button danda = new Button(this);
        danda.setText("\u0964");
        danda.setAllCaps(false);
        danda.setOnClickListener(v -> commitText("\u0964 "));
        utilityRow.addView(danda, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button enter = new Button(this);
        enter.setText("Enter");
        enter.setAllCaps(false);
        enter.setOnClickListener(v -> commitText("\n"));
        utilityRow.addView(enter, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button selectAll = new Button(this);
        selectAll.setText("All");
        selectAll.setAllCaps(false);
        selectAll.setOnClickListener(v -> selectAllText());
        utilityRow.addView(selectAll, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button nextKeyboard = new Button(this);
        nextKeyboard.setText("Next");
        nextKeyboard.setAllCaps(false);
        nextKeyboard.setOnClickListener(v -> switchToNextInputMethod(false));
        utilityRow.addView(nextKeyboard, new LinearLayout.LayoutParams(0, dp(46), 1));

        root.addView(utilityRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        addTextRow(root, new String[]{"\u0985", "\u0986", "\u0987", "\u0988", "\u0989", "\u098A", "\u098F", "\u0990", "\u0993", "\u0994"});
        addTextRow(root, new String[]{"\u0995", "\u0996", "\u0997", "\u0998", "\u0999", "\u099A", "\u099B", "\u099C", "\u099D", "\u099E"});
        addTextRow(root, new String[]{"\u099F", "\u09A0", "\u09A1", "\u09A2", "\u09A3", "\u09A4", "\u09A5", "\u09A6", "\u09A7", "\u09A8"});
        addTextRow(root, new String[]{"\u09AA", "\u09AB", "\u09AC", "\u09AD", "\u09AE", "\u09AF", "\u09B0", "\u09B2", "\u09B6", "\u09B7"});
        addTextRow(root, new String[]{"\u09B8", "\u09B9", "\u09DC", "\u09DD", "\u09DF", "\u09CE", "\u0982", "\u0983", "\u0981", "\u09CD"});
        addTextRow(root, new String[]{"\u09BE", "\u09BF", "\u09C0", "\u09C1", "\u09C2", "\u09C7", "\u09C8", "\u09CB", "\u09CC", "."});

        status = new TextView(this);
        status.setText("Ready");
        status.setGravity(Gravity.CENTER);
        status.setTextColor(0xff3c4043);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        return root;
    }

    @Override
    public void onDestroy() {
        stopRecorderQuietly();
        mainHandler.removeCallbacks(repeatDeleteRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toggleRecording() {
        if (isRecording) {
            stopAndTranscribe();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Open Voice Noter Keyboard app and grant mic permission");
            return;
        }

        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) {
            setStatus("Set endpoint in the Voice Noter Keyboard app");
            return;
        }

        try {
            audioFile = File.createTempFile("voice-keyboard-", ".m4a", getCacheDir());
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
            micButton.setText("Stop");
            setStatus("Listening...");
        } catch (Exception e) {
            stopRecorderQuietly();
            setStatus("Could not start recording: " + e.getMessage());
        }
    }

    private void stopAndTranscribe() {
        File finishedFile = audioFile;
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (RuntimeException e) {
            setStatus("Recording was too short");
            stopRecorderQuietly();
            return;
        }

        stopRecorderQuietly();
        micButton.setText("Start voice");
        setStatus("Transcribing...");

        executor.execute(() -> transcribe(finishedFile));
    }

    private void transcribe(File file) {
        try {
            if (file == null || !file.exists() || file.length() == 0) {
                postStatus("No audio captured");
                return;
            }

            String endpoint = getEndpoint() + "/api/transcribe";
            String audio = encodeFile(file);
            JSONObject body = new JSONObject();
            body.put("audio", audio);
            body.put("mimeType", MIME_TYPE);

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
                postStatus("Transcribe failed: HTTP " + code);
                return;
            }

            String text = new JSONObject(response.toString()).optString("text", "").trim();
            if (text.isEmpty()) {
                postStatus("No clear speech found");
                return;
            }

            mainHandler.post(() -> {
                commitText(text);
                setStatus("Inserted");
            });
        } catch (Exception e) {
            postStatus("Transcribe error: " + e.getMessage());
        } finally {
            if (file != null) file.delete();
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

    private void stopRecorderQuietly() {
        isRecording = false;
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
        recorder = null;
        if (micButton != null) micButton.setText("Start voice");
    }

    private void commitText(String text) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.commitText(text, 1);
    }

    private void deleteBackward() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        CharSequence selectedText = inputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            inputConnection.commitText("", 1);
            return;
        }

        inputConnection.deleteSurroundingText(1, 0);
    }

    private void selectAllText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.selectAll);
    }

    private void configureDeleteButton(Button button) {
        button.setOnClickListener(v -> deleteBackward());
        button.setOnLongClickListener(v -> {
            mainHandler.removeCallbacks(repeatDeleteRunnable);
            deleteBackward();
            mainHandler.postDelayed(repeatDeleteRunnable, 260);
            return true;
        });
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                mainHandler.removeCallbacks(repeatDeleteRunnable);
            }
            return false;
        });
    }

    private void addTextRow(LinearLayout root, String[] keys) {
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER);
        keyRow.setPadding(0, dp(4), 0, 0);

        for (String key : keys) {
            Button button = new Button(this);
            button.setText(key);
            button.setTextSize(18);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(v -> commitText(key));
            keyRow.addView(button, new LinearLayout.LayoutParams(0, dp(42), 1));
        }

        root.addView(keyRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    private String getEndpoint() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString(MainActivity.KEY_ENDPOINT, MainActivity.DEFAULT_ENDPOINT);
        if (endpoint == null) return "";
        endpoint = endpoint.trim();
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        return endpoint;
    }

    private void postStatus(String message) {
        mainHandler.post(() -> setStatus(message));
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
