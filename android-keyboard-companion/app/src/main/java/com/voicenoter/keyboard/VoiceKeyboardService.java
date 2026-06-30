package com.voicenoter.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
    private static final int KEY_HEIGHT = 34;
    private static final int STRIP_HEIGHT = 40;
    private static final int BG_COLOR = 0xfff7f3ec;
    private static final int ACCENT = 0xff1a73e8;
    private static final int MUTED = 0xff5f6368;
    private static final int BTN_IDLE_BG = 0xffe8eaed;
    private static final int BTN_IDLE_TEXT = 0xff202124;
    private static final int COLOR_RECORD = 0xff2e7d32;
    private static final int COLOR_LIVE = 0xffc62828;
    private static final int COLOR_ACTIVE_TEXT = 0xffffffff;
    private static final int BTN_CORNER_RADIUS_DP = 8;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable repeatDeleteRunnable = new Runnable() {
        @Override
        public void run() {
            deleteBackward();
            mainHandler.postDelayed(this, 55);
        }
    };
    private final Runnable voiceTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording || isLiveActive) {
                updateMicButtonAppearance();
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private MediaRecorder recorder;
    private File audioFile;
    private TextView status;
    private Button micButton;
    private ImageButton expandIconButton;
    private Button expandMicButton;
    private ImageButton stripDeleteButton;
    private LinearLayout keyboardPanel;
    private LinearLayout letterContainer;
    private Button langBnButton;
    private Button langEnButton;
    private Button langArButton;
    private Button numbersToggleButton;
    private boolean numbersMode = false;
    private SonioxLiveTranscriber liveTranscriber;
    private volatile boolean isRecording = false;
    private volatile boolean isLiveActive = false;
    private volatile boolean isLiveConnecting = false;
    private long voiceActivityStartMs = 0;
    private int voiceSessionGeneration = 0;
    private int liveStartOffset = 0;
    private String liveLastText = "";
    private String liveAnchorFinalBase = "";
    private boolean voiceOnlyMode = true;
    private String layoutLang = MainActivity.LANG_BN;
    private String voiceInputMode = MainActivity.MODE_RECORD;

    @Override
    public View onCreateInputView() {
        loadPreferences();
        prefetchSonioxKeyIfNeeded();
        return buildInputView();
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        prefetchSonioxKeyIfNeeded();
    }

    private View buildInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);
        root.addView(buildVoiceStrip(), matchWidthWrap());
        keyboardPanel = buildKeyboardPanel();
        root.addView(keyboardPanel, matchWidthWrap());
        applyVoiceOnlyVisibility();
        applyNavigationBarPadding(root);
        return root;
    }

    private void applyNavigationBarPadding(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bottom = bars.bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    @Override
    public void onFinishInput() {
        stopAllVoiceActivity(true);
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        stopAllVoiceActivity(true);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        stopAllVoiceActivity(true);
        super.onWindowHidden();
    }

    private void stopAllVoiceActivity(boolean cancelPendingWork) {
        if (cancelPendingWork) {
            voiceSessionGeneration++;
        }
        if (isLiveActive || liveTranscriber != null) {
            stopLive();
        }
        if (isRecording) {
            cancelRecordingQuietly();
        }
    }

    private LinearLayout buildVoiceStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(6), dp(4), dp(6), dp(4));

        langBnButton = langButton("\u09AC", MainActivity.LANG_BN);
        langEnButton = langButton("\u0987", MainActivity.LANG_EN);
        langArButton = langButton("\u098F", MainActivity.LANG_AR);
        strip.addView(langBnButton, weighted(STRIP_HEIGHT, 0.45f));
        strip.addView(langEnButton, weighted(STRIP_HEIGHT, 0.45f));
        strip.addView(langArButton, weighted(STRIP_HEIGHT, 0.45f));
        updateLangButtonStyles();

        micButton = new Button(this);
        micButton.setAllCaps(false);
        micButton.setTextSize(13);
        micButton.setTypeface(Typeface.DEFAULT_BOLD);
        micButton.setPadding(dp(8), dp(4), dp(8), dp(4));
        micButton.setOnClickListener(v -> toggleVoiceInput());
        micButton.setOnLongClickListener(v -> {
            if (isRecording || isLiveActive) return false;
            toggleVoiceInputMode();
            return true;
        });
        strip.addView(micButton, weighted(STRIP_HEIGHT, 1.2f));
        updateMicButtonAppearance();

        FrameLayout expandSlot = new FrameLayout(this);
        expandIconButton = iconButton(R.drawable.ic_keyboard);
        expandIconButton.setOnClickListener(v -> toggleKeyboardPanel());
        expandSlot.addView(expandIconButton, matchParentSquare());

        expandMicButton = new Button(this);
        expandMicButton.setText("MIC");
        expandMicButton.setAllCaps(false);
        expandMicButton.setTextSize(11);
        expandMicButton.setTypeface(Typeface.DEFAULT_BOLD);
        expandMicButton.setTextColor(MUTED);
        expandMicButton.setGravity(Gravity.CENTER);
        expandMicButton.setMinWidth(0);
        expandMicButton.setMinHeight(0);
        expandMicButton.setPadding(0, 0, 0, 0);
        expandMicButton.setOnClickListener(v -> toggleKeyboardPanel());
        expandSlot.addView(expandMicButton, matchParentSquare());

        strip.addView(expandSlot, weighted(STRIP_HEIGHT, 0.65f));
        updateExpandButtonIcon();

        stripDeleteButton = iconButton(R.drawable.ic_close_circle);
        configureDeleteButton(stripDeleteButton);
        strip.addView(stripDeleteButton, weighted(STRIP_HEIGHT, 0.65f));

        status = new TextView(this);
        status.setText("Ready");
        status.setTextSize(11);
        status.setTextColor(MUTED);
        status.setSingleLine(true);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        strip.addView(status, weighted(STRIP_HEIGHT, 1f));

        return strip;
    }

    private LinearLayout buildKeyboardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), 0, dp(4), dp(4));

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER);

        utilityRow.addView(actionKey("Space", () -> commitText(" "), KEY_HEIGHT), weighted(KEY_HEIGHT, 1.2f));
        numbersToggleButton = actionKey("123", this::toggleNumbersMode, KEY_HEIGHT);
        utilityRow.addView(numbersToggleButton, weighted(KEY_HEIGHT, 0.65f));
        utilityRow.addView(actionKey(",", () -> commitText(", "), KEY_HEIGHT), weighted(KEY_HEIGHT, 0.7f));
        utilityRow.addView(actionKey("Enter", () -> commitText("\n"), KEY_HEIGHT), weighted(KEY_HEIGHT, 1f));
        utilityRow.addView(actionKey("All", this::selectAllText, KEY_HEIGHT), weighted(KEY_HEIGHT, 0.8f));
        ImageButton nextKeyboard = iconButton(R.drawable.ic_globe);
        nextKeyboard.setOnClickListener(v -> switchToNextInputMethod(false));
        utilityRow.addView(nextKeyboard, weighted(KEY_HEIGHT, 0.65f));
        panel.addView(utilityRow, matchWidthWrap());

        letterContainer = new LinearLayout(this);
        letterContainer.setOrientation(LinearLayout.VERTICAL);
        panel.addView(letterContainer, matchWidthWrap());
        rebuildLetterRows();

        return panel;
    }

    private void rebuildLetterRows() {
        if (letterContainer == null) return;
        letterContainer.removeAllViews();
        String[][] rows = numbersMode ? numberRowsFor(layoutLang) : layoutRowsFor(layoutLang);
        for (String[] row : rows) {
            addTextRow(letterContainer, row);
        }
    }

    private String[][] numberRowsFor(String lang) {
        if (MainActivity.LANG_BN.equals(lang)) {
            return new String[][]{
                {"\u09E7", "\u09E8", "\u09E9", "\u09EA", "\u09EB", "\u09EC", "\u09ED", "\u09EE", "\u09EF", "\u09E6"},
            };
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            return new String[][]{
                {"\u0661", "\u0662", "\u0663", "\u0664", "\u0665", "\u0666", "\u0667", "\u0668", "\u0669", "\u0660"},
            };
        }
        return new String[][]{
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        };
    }

    private void toggleNumbersMode() {
        numbersMode = !numbersMode;
        updateNumbersToggleStyle();
        rebuildLetterRows();
        setStatus(numbersMode ? "Numbers" : languageLabel(layoutLang) + " keyboard");
    }

    private void updateNumbersToggleStyle() {
        if (numbersToggleButton == null) return;
        numbersToggleButton.setText(numbersMode ? "ABC" : "123");
        numbersToggleButton.setTypeface(numbersMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        numbersToggleButton.setTextColor(numbersMode ? ACCENT : BTN_IDLE_TEXT);
    }

    private String[][] layoutRowsFor(String lang) {
        if (MainActivity.LANG_EN.equals(lang)) {
            return new String[][]{
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
                {"z", "x", "c", "v", "b", "n", "m", ".", "?", "!"},
            };
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            return new String[][]{
                {"\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062D"},
                {"\u062C", "\u0634", "\u0633", "\u064A", "\u0628", "\u0644", "\u0627", "\u062A", "\u0646", "\u0645"},
                {"\u0643", "\u0637", "\u0630", "\u0621", "\u0624", "\u0631", "\u0649", "\u0629", "\u0648", "\u0632"},
            };
        }
        return new String[][]{
            {"\u0985", "\u0986", "\u0987", "\u0988", "\u0989", "\u098A", "\u0995", "\u0996", "\u0997", "\u0998"},
            {"\u0999", "\u099A", "\u099B", "\u099C", "\u099F", "\u09A0", "\u09A1", "\u09A2", "\u09A4", "\u09A5"},
            {"\u09A6", "\u09A7", "\u09A8", "\u09AA", "\u09AB", "\u09AC", "\u09AD", "\u09AE", "\u09AF", "\u09B0"},
            {"\u09B2", "\u09B6", "\u09B7", "\u09B8", "\u09B9", "\u09BE", "\u09BF", "\u09C0", "\u09C7", "\u0964"},
        };
    }

    private Button langButton(String label, String lang) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> setLayoutLanguage(lang));
        return button;
    }

    private ImageButton iconButton(int drawableRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setBackgroundColor(0x00000000);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        return button;
    }

    private Button actionKey(String label, Runnable action, int heightDp) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void setLayoutLanguage(String lang) {
        layoutLang = lang;
        numbersMode = false;
        updateLangButtonStyles();
        updateNumbersToggleStyle();
        rebuildLetterRows();
        savePreferences();
        setStatus(languageLabel(lang) + " keyboard");
    }

    private void updateLangButtonStyles() {
        styleLangButton(langBnButton, MainActivity.LANG_BN);
        styleLangButton(langEnButton, MainActivity.LANG_EN);
        styleLangButton(langArButton, MainActivity.LANG_AR);
    }

    private void styleLangButton(Button button, String lang) {
        if (button == null) return;
        boolean selected = layoutLang.equals(lang);
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setTextColor(selected ? ACCENT : MUTED);
    }

    private void toggleKeyboardPanel() {
        voiceOnlyMode = !voiceOnlyMode;
        applyVoiceOnlyVisibility();
        savePreferences();
    }

    private void applyVoiceOnlyVisibility() {
        if (keyboardPanel != null) {
            keyboardPanel.setVisibility(voiceOnlyMode ? View.GONE : View.VISIBLE);
        }
        updateExpandButtonIcon();
    }

    private void updateExpandButtonIcon() {
        if (expandIconButton == null || expandMicButton == null) return;
        expandIconButton.setVisibility(voiceOnlyMode ? View.VISIBLE : View.GONE);
        expandMicButton.setVisibility(voiceOnlyMode ? View.GONE : View.VISIBLE);
    }

    private String languageLabel(String lang) {
        if (MainActivity.LANG_EN.equals(lang)) return "English";
        if (MainActivity.LANG_AR.equals(lang)) return "Arabic";
        return "Bangla";
    }

    @Override
    public void onDestroy() {
        voiceSessionGeneration++;
        stopLiveQuietly();
        cancelRecordingQuietly();
        mainHandler.removeCallbacks(repeatDeleteRunnable);
        mainHandler.removeCallbacks(voiceTimerRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toggleVoiceInputMode() {
        voiceInputMode = MainActivity.MODE_LIVE.equals(voiceInputMode)
            ? MainActivity.MODE_RECORD
            : MainActivity.MODE_LIVE;
        savePreferences();
        updateMicButtonAppearance();
        setStatus(MainActivity.MODE_LIVE.equals(voiceInputMode) ? "Live mode" : "Voice mode");
        prefetchSonioxKeyIfNeeded();
    }

    private void toggleVoiceInput() {
        if (MainActivity.MODE_LIVE.equals(voiceInputMode)) {
            if (isLiveActive) stopLive();
            else startLive();
        } else if (isRecording) {
            stopAndTranscribe();
        } else {
            startRecording();
        }
    }

    private void updateMicButtonAppearance() {
        if (micButton == null) return;
        if (isLiveConnecting) {
            micButton.setText("...");
            setRoundedButtonBackground(micButton, COLOR_LIVE);
            micButton.setTextColor(COLOR_ACTIVE_TEXT);
        } else if (isLiveActive) {
            micButton.setText(formatVoiceElapsed());
            setRoundedButtonBackground(micButton, COLOR_LIVE);
            micButton.setTextColor(COLOR_ACTIVE_TEXT);
        } else if (isRecording) {
            micButton.setText(formatVoiceElapsed());
            setRoundedButtonBackground(micButton, COLOR_RECORD);
            micButton.setTextColor(COLOR_ACTIVE_TEXT);
        } else {
            micButton.setText(MainActivity.MODE_LIVE.equals(voiceInputMode) ? "Live" : "Voice");
            setRoundedButtonBackground(micButton, BTN_IDLE_BG);
            micButton.setTextColor(BTN_IDLE_TEXT);
        }
    }

    private String formatVoiceElapsed() {
        if (voiceActivityStartMs <= 0) return "0:00";
        long seconds = (System.currentTimeMillis() - voiceActivityStartMs) / 1000;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private void startVoiceTimer() {
        voiceActivityStartMs = System.currentTimeMillis();
        mainHandler.removeCallbacks(voiceTimerRunnable);
        mainHandler.postDelayed(voiceTimerRunnable, 1000);
    }

    private void stopVoiceTimer() {
        voiceActivityStartMs = 0;
        mainHandler.removeCallbacks(voiceTimerRunnable);
    }

    private void setRoundedButtonBackground(Button button, int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(BTN_CORNER_RADIUS_DP));
        button.setBackground(background);
    }

    private void startLive() {
        if (isLiveActive || isRecording) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Grant mic in app");
            return;
        }

        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) {
            setStatus("Set endpoint in app");
            return;
        }

        isLiveActive = true;
        isLiveConnecting = true;
        beginLiveAnchor();
        final int session = ++voiceSessionGeneration;
        updateMicButtonAppearance();
        setStatus("Connecting...");

        liveTranscriber = new SonioxLiveTranscriber();
        liveTranscriber.start(this, endpoint, layoutLang, new SonioxLiveTranscriber.Listener() {
            @Override
            public void onConnecting() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = true;
                    updateMicButtonAppearance();
                    setStatus("Connecting...");
                });
            }

            @Override
            public void onListening() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = false;
                    startVoiceTimer();
                    updateMicButtonAppearance();
                    setStatus("Live...");
                });
            }

            @Override
            public void onTranscriptUpdate(String finalText, String partialText) {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    if (isLiveConnecting) {
                        isLiveConnecting = false;
                        startVoiceTimer();
                        updateMicButtonAppearance();
                    }
                    updateLiveInsert(finalText, partialText);
                    setStatus("Live...");
                });
            }

            @Override
            public void onError(String message) {
                if (session != voiceSessionGeneration) return;
                mainHandler.post(() -> {
                    isLiveConnecting = false;
                    isLiveActive = false;
                    stopVoiceTimer();
                    liveTranscriber = null;
                    updateMicButtonAppearance();
                });
                postStatus(trimStatus(message));
            }

            @Override
            public void onStopped() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration) return;
                    isLiveConnecting = false;
                    isLiveActive = false;
                    stopVoiceTimer();
                    liveTranscriber = null;
                    updateMicButtonAppearance();
                });
            }
        });
    }

    private void stopLive() {
        if (!isLiveActive && liveTranscriber == null) return;
        isLiveActive = false;
        isLiveConnecting = false;
        stopVoiceTimer();
        finalizeLiveInsert();
        SonioxLiveTranscriber active = liveTranscriber;
        liveTranscriber = null;
        updateMicButtonAppearance();
        setStatus("Ready");
        if (active != null) active.stop();
    }

    private void stopLiveQuietly() {
        isLiveActive = false;
        isLiveConnecting = false;
        stopVoiceTimer();
        finalizeLiveInsert();
        if (liveTranscriber != null) {
            liveTranscriber.stop();
        }
        liveTranscriber = null;
    }

    private void beginLiveAnchor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            liveStartOffset = 0;
        } else {
            CharSequence before = ic.getTextBeforeCursor(100000, 0);
            liveStartOffset = before != null ? before.length() : 0;
        }
        liveLastText = "";
        liveAnchorFinalBase = "";
    }

    private int getCursorOffset(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(100000, 0);
        return before != null ? before.length() : 0;
    }

    private boolean hasLiveCursorMoved(InputConnection ic) {
        int cursor = getCursorOffset(ic);
        if (liveLastText.isEmpty()) {
            return cursor != liveStartOffset;
        }
        return cursor < liveStartOffset || cursor > liveStartOffset + liveLastText.length();
    }

    private void commitLiveAnchor(InputConnection ic, String finalText) {
        int userCursor = getCursorOffset(ic);
        int anchorStart = liveStartOffset;
        int oldLiveLen = liveLastText.length();

        ic.beginBatchEdit();
        if (!liveLastText.isEmpty()) {
            ic.setSelection(anchorStart, anchorStart + oldLiveLen);
            ic.finishComposingText();
        }
        ic.endBatchEdit();

        int newAnchor = userCursor;
        if (oldLiveLen > 0 && userCursor >= anchorStart && userCursor <= anchorStart + oldLiveLen) {
            newAnchor = anchorStart + oldLiveLen;
        }
        ic.setSelection(newAnchor, newAnchor);

        liveAnchorFinalBase = finalText != null ? finalText : "";
        liveStartOffset = newAnchor;
        liveLastText = "";
    }

    private void finalizeLiveInsert() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && !liveLastText.isEmpty()) {
            int end = liveStartOffset + liveLastText.length();
            ic.beginBatchEdit();
            ic.setSelection(liveStartOffset, end);
            ic.finishComposingText();
            ic.setSelection(end, end);
            ic.endBatchEdit();
        }
        liveLastText = "";
        liveAnchorFinalBase = "";
    }

    private void updateLiveInsert(String finalText, String partialText) {
        if (!isLiveActive) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String safeFinal = finalText != null ? finalText : "";
        String safePartial = partialText != null ? partialText : "";

        if (hasLiveCursorMoved(ic)) {
            commitLiveAnchor(ic, safeFinal);
        }

        int baseLen = Math.min(liveAnchorFinalBase.length(), safeFinal.length());
        String displayText = safeFinal.substring(baseLen) + safePartial;
        if (displayText.isEmpty() && liveLastText.isEmpty()) return;

        ic.beginBatchEdit();
        ic.setSelection(liveStartOffset, liveStartOffset + liveLastText.length());
        ic.setComposingText(displayText, 1);
        liveLastText = displayText;
        ic.endBatchEdit();
        setStatus("Live...");
    }

    private String trimStatus(String message) {
        if (message == null || message.isEmpty()) return "Live error";
        return message.length() > 24 ? message.substring(0, 24) : message;
    }

    private void startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Grant mic in app");
            return;
        }

        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) {
            setStatus("Set endpoint in app");
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
            voiceSessionGeneration++;
            startVoiceTimer();
            updateMicButtonAppearance();
            setStatus("Listening...");
        } catch (Exception e) {
            cancelRecordingQuietly();
            setStatus("Mic error");
        }
    }

    private void stopAndTranscribe() {
        if (!isRecording) return;
        final int session = voiceSessionGeneration;
        File finishedFile = audioFile;
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (RuntimeException e) {
            setStatus("Too short");
            cancelRecordingQuietly();
            return;
        }

        stopRecorderQuietly();
        setStatus("Transcribing...");

        executor.execute(() -> transcribe(finishedFile, session));
    }

    private void cancelRecordingQuietly() {
        voiceSessionGeneration++;
        stopRecorderQuietly();
        if (audioFile != null) {
            audioFile.delete();
            audioFile = null;
        }
    }

    private void transcribe(File file, int session) {
        try {
            if (session != voiceSessionGeneration) return;
            if (file == null || !file.exists() || file.length() == 0) {
                postStatus("No audio");
                return;
            }

            String endpoint = getEndpoint() + "/api/transcribe";
            String audio = encodeFile(file);
            JSONObject body = new JSONObject();
            body.put("audio", audio);
            body.put("mimeType", MIME_TYPE);
            body.put("language", layoutLang);

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
                postStatus("Failed " + code);
                return;
            }

            String text = new JSONObject(response.toString()).optString("text", "").trim();
            if (text.isEmpty()) {
                postStatus("No speech");
                return;
            }

            mainHandler.post(() -> {
                if (session != voiceSessionGeneration) return;
                commitText(text);
                setStatus("Inserted");
            });
        } catch (Exception e) {
            postStatus("Error");
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
        stopVoiceTimer();
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
        recorder = null;
        updateMicButtonAppearance();
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

    private void configureDeleteButton(View button) {
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
        keyRow.setPadding(0, dp(2), 0, 0);

        for (String key : keys) {
            Button button = new Button(this);
            button.setText(key);
            button.setTextSize(15);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(v -> commitText(key));
            keyRow.addView(button, weighted(KEY_HEIGHT, 1));
        }

        root.addView(keyRow, matchWidthWrap());
    }

    private FrameLayout.LayoutParams matchParentSquare() {
        return new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted(int heightDp, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(heightDp), weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        voiceOnlyMode = prefs.getBoolean(MainActivity.KEY_VOICE_ONLY, true);
        layoutLang = prefs.getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN);
        voiceInputMode = prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
        if (!MainActivity.MODE_RECORD.equals(voiceInputMode)
            && !MainActivity.MODE_LIVE.equals(voiceInputMode)) {
            voiceInputMode = MainActivity.MODE_RECORD;
        }
        if (!MainActivity.LANG_BN.equals(layoutLang)
            && !MainActivity.LANG_EN.equals(layoutLang)
            && !MainActivity.LANG_AR.equals(layoutLang)) {
            layoutLang = MainActivity.LANG_BN;
        }
    }

    private void savePreferences() {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_VOICE_ONLY, voiceOnlyMode)
            .putString(MainActivity.KEY_LAYOUT_LANG, layoutLang)
            .putString(MainActivity.KEY_VOICE_INPUT_MODE, voiceInputMode)
            .apply();
    }

    private void prefetchSonioxKeyIfNeeded() {
        if (!MainActivity.MODE_LIVE.equals(voiceInputMode)) return;
        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) return;
        SonioxKeyCache.prefetch(this, endpoint, executor);
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
