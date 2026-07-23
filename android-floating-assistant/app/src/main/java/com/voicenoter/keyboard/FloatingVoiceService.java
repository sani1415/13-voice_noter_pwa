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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingVoiceService extends Service implements FloatingVoiceSessionController.UiCallback {
    public static final String ACTION_RESET_POSITION = "com.voicenoter.floating.RESET_POSITION";
    public static final String ACTION_CLOSE = "com.voicenoter.floating.CLOSE";
    private static final String CHANNEL_ID = "voice_noter_floating";
    private static final int NOTIFICATION_ID = 786;
    private static final String MIME_TYPE = "audio/mp4";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private FrameLayout micWrap;
    private TextView mic;
    private WaveBarsView waveBars;
    private TextView arrow;
    private TextView modeButton;
    private TextView langButton;
    private SharedPreferences prefs;
    private FloatingVoiceSessionController voice;
    private MediaRecorder recorder;
    private File audioFile;
    private SonioxLiveTranscriber live;
    private boolean recording;
    private boolean transcribing;
    private boolean liveRunning;
    private boolean liveInserting;
    private boolean menuOpen;
    private final List<View> radialButtons = new ArrayList<>();
    private String liveText = "";
    private boolean textFieldFocused;
    private static FloatingVoiceService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        preferenceListener = (shared, key) -> {
            if (MainActivity.KEY_VOICE_INPUT_MODE.equals(key) && (voice == null || !voice.isBusy())) {
                main.post(this::setReady);
            }
            if (MainActivity.KEY_LAYOUT_LANG.equals(key) && langButton != null) {
                main.post(() -> langButton.setText(languageLabel()));
            }
            if (MainActivity.KEY_BUBBLE_VISIBILITY.equals(key)) {
                main.post(this::applyBubbleVisibility);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        voice = new FloatingVoiceSessionController(this, this);
        createNotificationChannel();
        startForegroundCompat();
        addFloatingBubble();
        voice.prefetchSonioxIfNeeded();
        applyBubbleVisibility();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CLOSE.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESET_POSITION.equals(action)) resetBubblePosition();
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    static boolean isRunning() { return instance != null; }

    static void onEditableFocusChanged(boolean focused) {
        FloatingVoiceService service = instance;
        if (service == null) return;
        service.main.post(() -> {
            service.textFieldFocused = focused;
            service.applyBubbleVisibility();
        });
    }

    private void applyBubbleVisibility() {
        if (overlay == null || prefs == null) return;
        boolean textOnly = MainActivity.BUBBLE_TEXT_FIELD.equals(
            prefs.getString(MainActivity.KEY_BUBBLE_VISIBILITY, MainActivity.BUBBLE_ALWAYS));
        boolean busy = voice != null && voice.isBusy();
        overlay.setVisibility(!textOnly || textFieldFocused || busy ? View.VISIBLE : View.GONE);
    }

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
        clampBubbleToScreen();

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);

        micWrap = new FrameLayout(this);
        micWrap.setBackground(round(Color.rgb(47, 111, 109), 56));
        mic = new TextView(this);
        mic.setText(modeLabel());
        mic.setTextSize(13);
        mic.setTypeface(mic.getTypeface(), android.graphics.Typeface.BOLD);
        mic.setTextColor(Color.WHITE);
        mic.setGravity(Gravity.CENTER);
        mic.setContentDescription("Start voice typing");
        micWrap.addView(mic, new FrameLayout.LayoutParams(-1, -1));
        waveBars = new WaveBarsView(this);
        waveBars.setVisibility(View.GONE);
        FrameLayout.LayoutParams waveLp = new FrameLayout.LayoutParams(dp(24), dp(17), Gravity.CENTER);
        waveLp.topMargin = -dp(6);
        micWrap.addView(waveBars, waveLp);
        overlay.addView(micWrap, new LinearLayout.LayoutParams(dp(56), dp(56)));

        arrow = new TextView(this);
        arrow.setText("‹");
        arrow.setTextSize(22);
        arrow.setTextColor(Color.WHITE);
        arrow.setGravity(Gravity.CENTER);
        arrow.setIncludeFontPadding(false);
        arrow.setPadding(0, 0, 0, dp(2));
        arrow.setContentDescription("Voice Noter options");
        arrow.setBackground(round(Color.rgb(74, 91, 90), 28));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(28), dp(36));
        arrowLp.leftMargin = dp(3);
        overlay.addView(arrow, arrowLp);

        micWrap.setOnTouchListener(new DragTouchListener());
        arrow.setOnClickListener(v -> toggleMenu());
        windowManager.addView(overlay, params);
    }

    private void resetBubblePosition() {
        closeMenuImmediately();
        params.x = dp(12);
        params.y = dp(220);
        clampBubbleToScreen();
        prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
        if (overlay != null && windowManager != null) {
            try { windowManager.updateViewLayout(overlay, params); } catch (Exception ignored) { }
        }
        toast("Bubble returned to the screen");
    }

    private void clampBubbleToScreen() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - dp(88));
        int maxY = Math.max(dp(24), metrics.heightPixels - dp(140));
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(dp(24), Math.min(params.y, maxY));
        prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
    }

    private class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean dragged;
        private boolean longPressTriggered;
        private final Runnable longPress = () -> {
            if (dragged || (voice != null && voice.isBusy())) return;
            longPressTriggered = true;
            micWrap.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            toggleMode();
        };

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (menuOpen) closeMenuImmediately();
                    startX = params.x; startY = params.y;
                    downX = event.getRawX(); downY = event.getRawY();
                    dragged = false; longPressTriggered = false;
                    main.postDelayed(longPress, 520);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                        dragged = true;
                        main.removeCallbacks(longPress);
                    }
                    params.x = startX + dx; params.y = startY + dy;
                    windowManager.updateViewLayout(overlay, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (dragged) {
                        clampBubbleToScreen();
                        windowManager.updateViewLayout(overlay, params);
                        prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
                    } else if (!longPressTriggered) {
                        toggleVoice();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
                default: return false;
            }
        }
    }

    private void toggleMenu() {
        if (menuOpen) closeMenuAnimated(); else openMenuAnimated();
    }

    private void openMenuAnimated() {
        if (menuOpen || micWrap == null || windowManager == null) return;
        menuOpen = true;
        // Both the main bubble and each radial button use TOP|START overlay
        // coordinates. Using screen coordinates here adds the status-bar inset
        // a second time on some devices and visibly shifts the radial centre.
        int micWidth = micWrap.getWidth() > 0 ? micWrap.getWidth() : dp(56);
        int micHeight = micWrap.getHeight() > 0 ? micWrap.getHeight() : dp(56);
        int centerX = params.x + micWidth / 2;
        int centerY = params.y + micHeight / 2;

        modeButton = addRadialButton(otherModeLabel(), -90, centerX, centerY, v -> toggleMode(), 0);
        langButton = addRadialButton(languageLabel(), -150, centerX, centerY, v -> cycleLanguage(), 25);
        addRadialButton("⚙", 150, centerX, centerY, v -> {
            closeMenuAnimated();
            openSettings();
        }, 50);
        addRadialButton("×", 90, centerX, centerY, v -> stopSelf(), 75);
        arrow.setText("›");
    }

    private void closeMenuAnimated() {
        if (!menuOpen) return;
        menuOpen = false;
        List<View> closing = new ArrayList<>(radialButtons);
        radialButtons.clear();
        for (View button : closing) {
            button.animate().alpha(0f).scaleX(0.45f).scaleY(0.45f).setDuration(130).start();
        }
        main.postDelayed(() -> removeRadialViews(closing), 145);
        arrow.setText("‹");
    }

    private void closeMenuImmediately() {
        if (!menuOpen && radialButtons.isEmpty()) return;
        menuOpen = false;
        List<View> closing = new ArrayList<>(radialButtons);
        radialButtons.clear();
        removeRadialViews(closing);
        arrow.setText("‹");
    }

    private TextView addRadialButton(String label, double angleDegrees, int centerX, int centerY,
                                     View.OnClickListener listener, long delayMs) {
        final int size = dp(44);
        final int radius = dp(72);
        double angle = Math.toRadians(angleDegrees);
        int x = centerX + (int) Math.round(Math.cos(angle) * radius) - size / 2;
        int y = centerY + (int) Math.round(Math.sin(angle) * radius) - size / 2;

        TextView button = radialItem(label, listener);
        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.START;
        buttonParams.x = x;
        buttonParams.y = y;
        windowManager.addView(button, buttonParams);
        radialButtons.add(button);
        button.setAlpha(0f);
        button.setScaleX(0.35f);
        button.setScaleY(0.35f);
        button.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(180).start();
        return button;
    }

    private TextView radialItem(String label, View.OnClickListener listener) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(label.length() > 3 ? 11 : 13);
        item.setTypeface(item.getTypeface(), android.graphics.Typeface.BOLD);
        item.setTextColor(Color.rgb(32, 33, 36));
        item.setGravity(Gravity.CENTER);
        item.setBackground(round(Color.WHITE, 44));
        item.setElevation(dp(8));
        item.setOnClickListener(listener);
        return item;
    }

    private void removeRadialViews(List<View> views) {
        if (windowManager == null) return;
        for (View view : views) {
            try { windowManager.removeView(view); } catch (Exception ignored) { }
        }
    }

    private void toggleMode() {
        if (voice != null && voice.isBusy()) return;
        String next = MainActivity.MODE_LIVE.equals(mode()) ? MainActivity.MODE_RECORD : MainActivity.MODE_LIVE;
        prefs.edit().putString(MainActivity.KEY_VOICE_INPUT_MODE, next).apply();
        if (modeButton != null) modeButton.setText(otherModeLabel());
        setReady();
        if (voice != null) voice.prefetchSonioxIfNeeded();
        toast(MainActivity.MODE_LIVE.equals(next) ? "Live mode" : "Record mode");
    }

    private void cycleLanguage() {
        if (voice != null && voice.isBusy()) return;
        String current = language();
        List<String> quick = LanguageRegistry.parseQuick(
            prefs.getString(MainActivity.KEY_QUICK_LANGS, LanguageRegistry.DEFAULT_QUICK));
        int index = quick.indexOf(current);
        String next = quick.get(index < 0 || index + 1 >= quick.size() ? 0 : index + 1);
        prefs.edit().putString(MainActivity.KEY_LAYOUT_LANG, next).apply();
        if (langButton != null) langButton.setText(languageLabel());
    }

    private void openSettings() {
        Intent intent = new Intent(this, FloatingMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void toggleVoice() {
        if (voice != null) voice.toggleVoice();
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
            result = VoicePunctuation.applyForLanguage(
                new JSONObject(response.toString()).optString("text", "").trim(), language());
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
                liveText = VoicePunctuation.applyForLanguage((finalText + partialText).trim(), language());
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

    private void setReady() { setMicState(modeLabel(), Color.rgb(47, 111, 109), "Start voice typing", false); }
    private void setMicState(String text, int color, String description) {
        setMicState(text, color, description, false);
    }
    private void setMicState(String text, int color, String description, boolean showWave) {
        main.post(() -> {
            if (mic == null || micWrap == null) return;
            micWrap.setBackground(round(color, 56));
            mic.setText(showWave ? modeLabel() : text);
            mic.setTextSize(showWave ? 9 : 13);
            mic.setGravity(showWave ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
            mic.setPadding(0, 0, 0, showWave ? dp(6) : 0);
            micWrap.setContentDescription(description);
            if (waveBars != null) {
                if (showWave) {
                    waveBars.setBarColor(Color.WHITE);
                    waveBars.startAnimating();
                } else {
                    waveBars.stopAnimating();
                }
            }
            applyBubbleVisibility();
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
    private String otherModeLabel() { return MainActivity.MODE_LIVE.equals(mode()) ? "REC" : "LIVE"; }
    private String languageLabel() { return LanguageRegistry.find(language()).shortLabel(); }

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

    @Override public void onIdle(boolean liveMode) { setReady(); }
    @Override public void onRecording() { setMicState("REC", Color.rgb(13, 148, 136), "Recording — tap to stop", true); }
    @Override public void onTranscribing() { setMicState("REC", Color.rgb(239, 108, 0), "Transcribing", true); }
    @Override public void onLiveConnecting() { setMicState("LIVE", Color.rgb(239, 108, 0), "Connecting live voice", true); }
    @Override public void onLiveListening() { setMicState("LIVE", Color.rgb(198, 40, 40), "Live listening — tap to stop", true); }
    @Override public void onStatus(String message) { toast(message); }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        closeMenuImmediately();
        if (waveBars != null) waveBars.stopAnimating();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (voice != null) { voice.destroy(); voice = null; }
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
