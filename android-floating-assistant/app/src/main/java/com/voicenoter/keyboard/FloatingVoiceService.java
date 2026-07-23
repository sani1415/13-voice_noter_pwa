package com.voicenoter.keyboard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
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

public class FloatingVoiceService extends Service {
    private static final String CHANNEL_ID = "voice_noter_floating";
    private static final int NOTIFICATION_ID = 786;
    private static final String MIME_TYPE = "audio/mp4";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private LinearLayout menu;
    private TextView mic;
    private TextView arrow;
    private TextView modeButton;
    private TextView langButton;
    private SharedPreferences prefs;
    private MediaRecorder recorder;
    private File audioFile;
    private SonioxLiveTranscriber live;
    private boolean recording;
    private boolean transcribing;
    private boolean liveRunning;
    private boolean liveInserting;
    private String liveText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        createNotificationChannel();
        startForegroundCompat();
        addFloatingBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat() {
        Intent open = new Intent(this, FloatingMainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Noter Floating")
            .setContentText("Voice bubble is ready")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            "Floating voice assistant", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the Voice Noter microphone bubble available");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void addFloatingBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt("bubble_x", dp(12));
        params.y = prefs.getInt("bubble_y", dp(220));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setVisibility(View.GONE);
        menu.setPadding(dp(6), dp(4), dp(5), dp(4));
        menu.setBackground(round(Color.rgb(255, 255, 255), 24));

        modeButton = menuItem(modeLabel(), v -> toggleMode());
        langButton = menuItem(languageLabel(), v -> cycleLanguage());
        menu.addView(modeButton);
        menu.addView(langButton);
        menu.addView(menuItem("⚙", v -> openSettings()));
        menu.addView(menuItem("×", v -> stopSelf()));
        overlay.addView(menu);

        mic = new TextView(this);
        mic.setText(modeLabel());
        mic.setTextSize(13);
        mic.setTypeface(mic.getTypeface(), android.graphics.Typeface.BOLD);
        mic.setTextColor(Color.WHITE);
        mic.setGravity(Gravity.CENTER);
        mic.setContentDescription("Start voice typing");
        mic.setBackground(round(Color.rgb(47, 111, 109), 56));
        overlay.addView(mic, new LinearLayout.LayoutParams(dp(56), dp(56)));

        arrow = new TextView(this);
        arrow.setText("‹");
        arrow.setTextSize(22);
        arrow.setTextColor(Color.WHITE);
        arrow.setGravity(Gravity.CENTER);
        arrow.setContentDescription("Voice Noter options");
        arrow.setBackground(round(Color.rgb(74, 91, 90), 28));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(28), dp(36));
        arrowLp.leftMargin = dp(3);
        overlay.addView(arrow, arrowLp);

        mic.setOnTouchListener(new DragTouchListener());
        arrow.setOnClickListener(v -> toggleMenu());
        windowManager.addView(overlay, params);
    }

    private class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x; startY = params.y;
                    downX = event.getRawX(); downY = event.getRawY(); dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) dragged = true;
                    params.x = startX + dx; params.y = startY + dy;
                    windowManager.updateViewLayout(overlay, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragged) {
                        prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
                    } else {
                        toggleVoice();
                    }
                    return true;
                default: return false;
            }
        }
    }

    private void toggleMenu() {
        boolean show = menu.getVisibility() != View.VISIBLE;
        menu.setVisibility(show ? View.VISIBLE : View.GONE);
        arrow.setText(show ? "›" : "‹");
    }

    private void toggleMode() {
        if (recording || liveRunning || transcribing) return;
        String next = MainActivity.MODE_LIVE.equals(mode()) ? MainActivity.MODE_RECORD : MainActivity.MODE_LIVE;
        prefs.edit().putString(MainActivity.KEY_VOICE_INPUT_MODE, next).apply();
        modeButton.setText(modeLabel());
        setReady();
        toast(MainActivity.MODE_LIVE.equals(next) ? "Live mode" : "Record mode");
    }

    private void cycleLanguage() {
        if (recording || liveRunning || transcribing) return;
        String current = language();
        String next = MainActivity.LANG_BN.equals(current) ? MainActivity.LANG_EN
            : MainActivity.LANG_EN.equals(current) ? MainActivity.LANG_AR : MainActivity.LANG_BN;
        prefs.edit().putString(MainActivity.KEY_LAYOUT_LANG, next).apply();
        langButton.setText(languageLabel());
    }

    private void openSettings() {
        Intent intent = new Intent(this, FloatingMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void toggleVoice() {
        if (transcribing) { toast("Still transcribing…"); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Grant microphone permission in settings"); openSettings(); return;
        }
        if (MainActivity.MODE_LIVE.equals(mode())) {
            if (liveRunning) stopLive(); else startLive();
        } else {
            if (recording) stopAndTranscribe(); else startRecording();
        }
    }

    private void startRecording() {
        try {
            audioFile = File.createTempFile("voice-floating-", ".m4a", getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare(); recorder.start(); recording = true;
            setMicState("REC", Color.rgb(198, 40, 40), "Stop and transcribe");
        } catch (Exception error) {
            releaseRecorder(); toast("Microphone could not start");
        }
    }

    private void stopAndTranscribe() {
        File finished = audioFile;
        try { recorder.stop(); }
        catch (RuntimeException tooShort) { releaseRecorder(); toast("Recording was too short"); return; }
        releaseRecorder(); transcribing = true;
        setMicState("…", Color.rgb(239, 108, 0), "Transcribing");
        executor.execute(() -> transcribe(finished));
    }

    private void transcribe(File file) {
        String result = null; String error = null;
        try {
            if (file == null || !file.exists() || file.length() == 0) throw new Exception("No audio");
            JSONObject body = new JSONObject();
            body.put("audio", encodeFile(file));
            body.put("mimeType", MIME_TYPE);
            body.put("language", language());
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint() + "/api/transcribe").openConnection();
            connection.setRequestMethod("POST"); connection.setConnectTimeout(20000); connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) response.append(line);
            if (code < 200 || code >= 300) throw new Exception("Server error " + code);
            result = VoicePunctuation.apply(new JSONObject(response.toString()).optString("text", "").trim());
            if (result.isEmpty()) throw new Exception("No speech found");
        } catch (Exception problem) {
            error = problem.getMessage() == null ? "Transcription failed" : problem.getMessage();
        } finally { if (file != null) file.delete(); }
        final String transcript = result; final String message = error;
        main.post(() -> {
            transcribing = false; setReady();
            if (transcript != null) VoiceAccessibilityService.insertTranscript(this, transcript);
            else toast(message);
        });
    }

    private void startLive() {
        liveText = ""; liveRunning = true;
        liveInserting = VoiceAccessibilityService.beginLiveInsertion(this);
        if (!liveInserting) toast("Keep the cursor in an editable field; text will insert when stopped");
        setMicState("LIVE", Color.rgb(239, 108, 0), "Connecting live voice");
        live = new SonioxLiveTranscriber();
        live.start(this, endpoint(), language(), new SonioxLiveTranscriber.Listener() {
            @Override public void onConnecting() { }
            @Override public void onListening() { setMicState("LIVE", Color.rgb(198, 40, 40), "Stop live voice"); }
            @Override public void onTranscriptUpdate(String finalText, String partialText) {
                liveText = VoicePunctuation.apply((finalText + partialText).trim());
                if (liveInserting && !VoiceAccessibilityService.updateLiveTranscript(liveText)) {
                    liveInserting = false;
                    VoiceAccessibilityService.finishLiveInsertion();
                }
            }
            @Override public void onError(String message) {
                liveRunning = false;
                VoiceAccessibilityService.finishLiveInsertion();
                liveInserting = false;
                setReady(); toast(message);
            }
            @Override public void onStopped() { }
        });
    }

    private void stopLive() {
        liveRunning = false;
        if (live != null) { live.stop(); live = null; }
        setReady();
        boolean streamedIntoField = liveInserting && VoiceAccessibilityService.finishLiveInsertion();
        liveInserting = false;
        if (liveText.isEmpty()) {
            toast("No speech found");
        } else if (!streamedIntoField) {
            VoiceAccessibilityService.insertTranscript(this, liveText);
        }
        liveText = "";
    }

    private String encodeFile(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0; int read;
            while (offset < bytes.length && (read = in.read(bytes, offset, bytes.length - offset)) > 0) offset += read;
            if (offset != bytes.length) throw new Exception("Audio read failed");
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private void releaseRecorder() {
        recording = false;
        if (recorder != null) { try { recorder.release(); } catch (Exception ignored) { } recorder = null; }
        audioFile = null;
    }

    private void setReady() { setMicState(modeLabel(), Color.rgb(47, 111, 109), "Start voice typing"); }
    private void setMicState(String text, int color, String description) {
        main.post(() -> {
            if (mic == null) return;
            mic.setText(text); mic.setBackground(round(color, 56)); mic.setContentDescription(description);
        });
    }

    private String endpoint() {
        String value = prefs.getString(MainActivity.KEY_ENDPOINT, MainActivity.DEFAULT_ENDPOINT).trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
    private String mode() { return prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD); }
    private String language() { return prefs.getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN); }
    private String modeLabel() { return MainActivity.MODE_LIVE.equals(mode()) ? "LIVE" : "REC"; }
    private String languageLabel() {
        return MainActivity.LANG_EN.equals(language()) ? "EN" : MainActivity.LANG_AR.equals(language()) ? "AR" : "BN";
    }

    private TextView menuItem(String text, View.OnClickListener click) {
        TextView item = new TextView(this);
        item.setText(text); item.setTextSize(13); item.setTextColor(Color.rgb(32, 33, 36));
        item.setGravity(Gravity.CENTER); item.setOnClickListener(click);
        item.setPadding(dp(8), 0, dp(8), 0);
        item.setBackground(round(Color.rgb(239, 237, 232), 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.rightMargin = dp(4); item.setLayoutParams(lp);
        return item;
    }

    private GradientDrawable round(int color, int sizeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(sizeDp) / 2f);
        return shape;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { main.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }

    @Override
    public void onDestroy() {
        if (live != null) live.stop();
        if (recording && recorder != null) { try { recorder.stop(); } catch (Exception ignored) { } }
        releaseRecorder();
        executor.shutdownNow();
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
