package com.voicenoter.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingBubbleService extends Service implements VoiceSessionController.UiCallback {
    static final String ACTION_START = "com.voicenoter.assistant.START_BUBBLE";
    static final String ACTION_STOP = "com.voicenoter.assistant.STOP_BUBBLE";

    private static final String CHANNEL_ID = "voice_assistant_bubble";
    private static final int NOTIF_ID = 42;

    private static final int COLOR_RECORD = 0xFF0D9488;
    private static final int COLOR_RECORD_SOFT = 0xFFE8F5F2;
    private static final int COLOR_LIVE = 0xFFDC2626;
    private static final int COLOR_LIVE_SOFT = 0xFFFEE2E2;
    private static final int COLOR_ACTIVE_ICON = 0xFFFFFFFF;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
        (prefs, key) -> {
            if (MainActivity.KEY_VOICE_INPUT_MODE.equals(key)) {
                mainHandler.post(this::refreshModeFromPrefs);
            } else if (MainActivity.KEY_BUBBLE_VISIBILITY.equals(key)) {
                mainHandler.post(this::applyBubbleVisibility);
            }
        };

    private WindowManager windowManager;
    private View bubbleView;
    private WindowManager.LayoutParams layoutParams;
    private FrameLayout micWrap;
    private ImageView micIcon;
    private WaveBarsView waveBars;
    private TextView modeLabel;
    private ImageView settingsBtn;
    private VoiceSessionController voice;
    private SharedPreferences prefs;
    private boolean bubbleAdded;
    private boolean textFieldFocused;
    private static FloatingBubbleService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        voice = new VoiceSessionController(this, this);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow draw over other apps first", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        addBubble();
        voice.prefetchSonioxIfNeeded();
        onIdle(voice.isLiveMode());
        applyBubbleVisibility();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        if (waveBars != null) waveBars.stopAnimating();
        if (voice != null) voice.destroy();
        removeBubble();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean isRunning() {
        return instance != null;
    }

    /** Called from AccessibilityService when editable focus changes. */
    static void onEditableFocusChanged(boolean focused) {
        FloatingBubbleService svc = instance;
        if (svc == null) return;
        svc.mainHandler.post(() -> {
            svc.textFieldFocused = focused;
            svc.applyBubbleVisibility();
        });
    }

    static void start(Context context) {
        Intent i = new Intent(context, FloatingBubbleService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, FloatingBubbleService.class));
    }

    private boolean isTextFieldVisibilityMode() {
        if (prefs == null) return false;
        return MainActivity.BUBBLE_TEXT_FIELD.equals(
            prefs.getString(MainActivity.KEY_BUBBLE_VISIBILITY, MainActivity.BUBBLE_ALWAYS)
        );
    }

    private void applyBubbleVisibility() {
        if (bubbleView == null) return;
        boolean show;
        if (!isTextFieldVisibilityMode()) {
            show = true;
        } else {
            boolean busy = voice != null && voice.isBusy();
            show = textFieldFocused || busy;
        }
        bubbleView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void addBubble() {
        if (bubbleAdded || windowManager == null) return;
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null);
        micWrap = bubbleView.findViewById(R.id.mic_wrap);
        micIcon = bubbleView.findViewById(R.id.mic_icon);
        waveBars = bubbleView.findViewById(R.id.wave_bars);
        modeLabel = bubbleView.findViewById(R.id.mode_label);
        settingsBtn = bubbleView.findViewById(R.id.settings_btn);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = dp(16);
        layoutParams.y = dp(160);

        setupDragAndTap();
        settingsBtn.setOnClickListener(v -> openSettings());

        windowManager.addView(bubbleView, layoutParams);
        bubbleAdded = true;
    }

    private void removeBubble() {
        if (bubbleAdded && bubbleView != null && windowManager != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {
            }
        }
        bubbleAdded = false;
        bubbleView = null;
        micWrap = null;
        micIcon = null;
        waveBars = null;
        modeLabel = null;
        settingsBtn = null;
    }

    private void setupDragAndTap() {
        final int touchSlop = dp(8);
        final int longPressMs = 450;
        micWrap.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float touchX;
            private float touchY;
            private boolean dragging;
            private boolean longPressFired;
            private boolean liveStartedOnDown;
            private final Runnable longPressRunnable = () -> {
                if (dragging || longPressFired || voice == null) return;
                longPressFired = true;
                if (liveStartedOnDown) {
                    // Long-press is mode switch — cancel the early live start.
                    voice.stopQuietly();
                    liveStartedOnDown = false;
                }
                if (micWrap != null) {
                    micWrap.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
                voice.toggleMode();
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = layoutParams.x;
                        startY = layoutParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        dragging = false;
                        longPressFired = false;
                        liveStartedOnDown = false;
                        mainHandler.removeCallbacks(longPressRunnable);
                        mainHandler.postDelayed(longPressRunnable, longPressMs);
                        // Overlap mic/WS startup with the press itself (live mode only).
                        if (voice != null && voice.isLiveMode() && !voice.isBusy()) {
                            liveStartedOnDown = voice.startLiveIfIdle();
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                            mainHandler.removeCallbacks(longPressRunnable);
                            if (liveStartedOnDown) {
                                voice.stopQuietly();
                                liveStartedOnDown = false;
                            }
                        }
                        if (dragging) {
                            layoutParams.x = startX + Math.round(dx);
                            layoutParams.y = startY + Math.round(dy);
                            windowManager.updateViewLayout(bubbleView, layoutParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        mainHandler.removeCallbacks(longPressRunnable);
                        if (!dragging && !longPressFired) {
                            if (liveStartedOnDown) {
                                // Already listening from ACTION_DOWN — keep session open.
                                liveStartedOnDown = false;
                            } else if (voice != null) {
                                voice.toggleVoice();
                            }
                        }
                        liveStartedOnDown = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(longPressRunnable);
                        if (liveStartedOnDown && voice != null) {
                            voice.stopQuietly();
                        }
                        liveStartedOnDown = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void refreshModeFromPrefs() {
        if (voice == null || bubbleView == null) return;
        // Don't interrupt an active session UI — idle appearance updates when session ends.
        if (voice.isBusy()) return;
        voice.prefetchSonioxIfNeeded();
        onIdle(voice.isLiveMode());
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.bubble_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void styleMic(int bg, int iconTint, int iconRes, boolean liveMode, boolean showWave) {
        if (micWrap == null || micIcon == null) return;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(bg);
        shape.setStroke(dp(2), iconTint == COLOR_ACTIVE_ICON ? 0x33FFFFFF : iconTint);
        micWrap.setBackground(shape);

        if (modeLabel != null) {
            modeLabel.setText(liveMode ? "LIVE" : "REC");
            modeLabel.setTextColor(iconTint);
        }

        if (showWave && waveBars != null) {
            micIcon.setVisibility(View.INVISIBLE);
            waveBars.setBarColor(iconTint);
            waveBars.startAnimating();
        } else {
            if (waveBars != null) waveBars.stopAnimating();
            micIcon.setVisibility(View.VISIBLE);
            micIcon.setImageResource(iconRes);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                micIcon.setImageTintList(ColorStateList.valueOf(iconTint));
            } else {
                micIcon.setColorFilter(iconTint);
            }
        }
    }

    @Override
    public void onIdle(boolean liveMode) {
        if (liveMode) {
            styleMic(COLOR_LIVE_SOFT, COLOR_LIVE, R.drawable.ic_live, true, false);
        } else {
            styleMic(COLOR_RECORD_SOFT, COLOR_RECORD, R.drawable.ic_mic, false, false);
        }
        applyBubbleVisibility();
    }

    @Override
    public void onRecording() {
        styleMic(COLOR_RECORD, COLOR_ACTIVE_ICON, R.drawable.ic_mic, false, false);
        applyBubbleVisibility();
    }

    @Override
    public void onTranscribing() {
        styleMic(COLOR_RECORD, COLOR_ACTIVE_ICON, R.drawable.ic_mic, false, true);
        applyBubbleVisibility();
    }

    @Override
    public void onLiveConnecting() {
        styleMic(COLOR_LIVE, COLOR_ACTIVE_ICON, R.drawable.ic_live, true, false);
        applyBubbleVisibility();
    }

    @Override
    public void onLiveListening() {
        styleMic(COLOR_LIVE, COLOR_ACTIVE_ICON, R.drawable.ic_live, true, false);
        applyBubbleVisibility();
    }

    @Override
    public void onStatus(String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
