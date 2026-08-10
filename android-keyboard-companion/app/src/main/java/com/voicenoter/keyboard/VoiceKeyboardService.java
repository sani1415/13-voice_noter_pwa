package com.voicenoter.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.PopupWindow;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
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
    private static final int UTILITY_KEY_HEIGHT = 28;
    private static final int STRIP_HEIGHT = 40;
    private static final int STRIP_ICON_W = 40;   // same on BN / EN / AR
    private static final int STRIP_MIC_W = 80;    // ~2 bottom letter keys
    private static final int COLOR_RECORD = 0xff2e7d32;
    private static final int COLOR_RECORD_SOFT = 0xffe8f5e9;
    private static final int COLOR_LIVE = 0xffc62828;
    private static final int COLOR_LIVE_SOFT = 0xffffebee;
    private static final int COLOR_ACTIVE_TEXT = 0xffffffff;
    private static final int BTN_CORNER_RADIUS_DP = 8;
    private static final int MAX_PRESETS = 24;

    // Sentinel keys used inside letter rows to flip between the main and "more" page,
    // and to trigger delete / shift behaviour from within a row array.
    private static final String TOK_MORE = "\u0001MORE";
    private static final String TOK_BACK = "\u0001BACK";
    private static final String TOK_DELETE = "\u0001DELETE";
    private static final String TOK_SHIFT = "\u0001SHIFT";

    private static final String[] BN_VOWEL_ROW = {
        "\u0985", "\u0986", "\u0987", "\u0988", "\u0989", "\u098A",
        "\u098F", "\u0990", "\u0993", "\u0994"
    };
    private static final String[] BN_DANDA_POPUP_SYMBOLS = {
        "\u09CC", "\u09C8", "\u09C3", "\u09C2", "\u0982", "\u0983", "\u0981",
        "\u09CE", "\u09E0", "\u098C", "\u09F3", "\u20AC", "\u2018", "\u2019",
        "\u0965"
    };
    private static final String[] BN_KAR_SUFFIXES = {
        "", "\u09BE", "\u09BF", "\u09C0", "\u09C1", "\u09C2",
        "\u09C7", "\u09C8", "\u09CB", "\u09CC"
    };

    // Long-press alternate characters for the roman QWERTY rows (EN + phonetic BN)
    // and a few native Bangla letters that have an easy related form.
    private static final java.util.Map<String, String[]> EN_LONG_PRESS = new java.util.HashMap<>();
    private static final java.util.Map<String, String[]> BN_LONG_PRESS = new java.util.HashMap<>();
    static {
        EN_LONG_PRESS.put("a", new String[]{"\u00E1", "\u00E0", "\u00E2", "\u00E4", "\u00E5", "\u00E3"});
        EN_LONG_PRESS.put("e", new String[]{"\u00E9", "\u00E8", "\u00EA", "\u00EB"});
        EN_LONG_PRESS.put("i", new String[]{"\u00ED", "\u00EC", "\u00EE", "\u00EF"});
        EN_LONG_PRESS.put("o", new String[]{"\u00F3", "\u00F2", "\u00F4", "\u00F6", "\u00F5"});
        EN_LONG_PRESS.put("u", new String[]{"\u00FA", "\u00F9", "\u00FB", "\u00FC"});
        EN_LONG_PRESS.put("n", new String[]{"\u00F1"});
        EN_LONG_PRESS.put("c", new String[]{"\u00E7"});
        EN_LONG_PRESS.put("s", new String[]{"\u00DF", "\u00A7"});
        EN_LONG_PRESS.put("y", new String[]{"\u00FD"});
        EN_LONG_PRESS.put("'", new String[]{"\u2018", "\u2019", "\u201C", "\u201D", "`"});
        BN_LONG_PRESS.put("\u09A4", new String[]{"\u09CE"});
    }

    // Theme colours, resolved from the selected preset in loadPreferences().
    private int themeBg = 0xfff7f3ec;
    private int themeAccent = 0xff2f6f6d;
    private int themeMuted = 0xff5f6368;
    private int themeKeyBg = 0xffffffff;
    private int themeKeyText = 0xff202124;
    private int themeKeyStroke = 0xffe3ddd0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View activeDeleteSource;
    private final Runnable repeatDeleteRunnable = new Runnable() {
        @Override
        public void run() {
            performSingleDelete(activeDeleteSource);
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
    private TextView micLabel;
    private ImageView micModeIcon;
    private TextView micHoldHint;
    private ImageButton expandArrowButton;
    private ImageButton stripPresetButton;
    private ImageButton stripSettingsButton;
    private ImageButton morePageIcon;
    private View stripDeleteKey;
    private View stripMicKeyWrap;
    private View utilityMoreKey;
    private View karToggleKey;
    private LinearLayout bnRow1Layout;
    private LinearLayout keyboardPanel;
    private LinearLayout presetComposeBar;
    private EditText presetComposeInput;
    private LinearLayout letterContainer;
    private LinearLayout suggestionBar;
    private final TextView[] suggestionViews = new TextView[3];
    // Best suggestion goes to the centre slot, second-best left, third right.
    private static final int[] SUGGESTION_SLOT_ORDER = {1, 0, 2};
    private volatile BanglaDictionary bnDictionary;
    private boolean bnDictionaryLoadStarted = false;
    private Button shiftKeyView;
    private Button enterKeyButton;
    private Button rightPunctButton;
    private Button spaceKeyButton;
    private final java.util.List<Button> qwertyLetterButtons = new java.util.ArrayList<>();
    private java.util.List<String> enabledLangs = new java.util.ArrayList<>();
    private boolean morePage = false;
    private boolean presetComposeActive = false;
    private String appliedConfigSig = "";
    private SonioxLiveTranscriber liveTranscriber;
    private volatile boolean isRecording = false;
    private volatile boolean isLiveActive = false;
    private volatile boolean isLiveConnecting = false;
    private volatile boolean isTranscribing = false;
    private long voiceActivityStartMs = 0;
    private int voiceSessionGeneration = 0;
    private int liveStartOffset = 0;
    private String liveLastText = "";
    private String liveAnchorFinalBase = "";
    private boolean voiceOnlyMode = false;
    private String layoutLang = MainActivity.LANG_BN;
    private String voiceInputMode = MainActivity.MODE_RECORD;
    private String bnKarBase = null;
    private boolean bnRow1Vowels = true;

    // Comfort settings.
    private boolean hapticEnabled = true;
    private int letterKeyHeightDp = 42;
    private int bottomKeyHeightDp = 50;
    private int shiftState = 0; // 0 = off, 1 = shift-once, 2 = caps-lock
    private boolean lastKeyWasSpace = false;
    private long lastSpaceCommitMs = 0;
    private EditorInfo currentEditorInfo;

    @Override
    public View onCreateInputView() {
        loadPreferences();
        prefetchSonioxKeyIfNeeded();
        loadBnDictionaryIfNeeded();
        return buildInputView();
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        currentEditorInfo = attribute;
        if (settingsChangedSinceBuild()) {
            loadPreferences();
            setInputView(buildInputView());
        } else {
            // Settings Activity can change Record/Live while the IME process stays alive.
            // Always re-read that so the mic label matches the saved preference.
            reloadVoiceInputModeFromPrefs();
        }
        refreshEnterKeyLabel();
        updateMicButtonAppearance();
        prefetchSonioxKeyIfNeeded();
        loadBnDictionaryIfNeeded();
        refreshSuggestions();
    }

    private void reloadVoiceInputModeFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
        if (!MainActivity.MODE_RECORD.equals(mode) && !MainActivity.MODE_LIVE.equals(mode)) {
            mode = MainActivity.MODE_RECORD;
        }
        voiceInputMode = mode;
        hapticEnabled = prefs.getBoolean(MainActivity.KEY_HAPTIC, true);
    }

    private boolean settingsChangedSinceBuild() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String sig = configSignature(
            prefs.getString(MainActivity.KEY_THEME, MainActivity.DEFAULT_THEME),
            prefs.getString(MainActivity.KEY_ENABLED_LANGS, MainActivity.DEFAULT_ENABLED_LANGS),
            prefs.getBoolean(MainActivity.KEY_HAPTIC, true),
            prefs.getString(MainActivity.KEY_KEY_SIZE, MainActivity.DEFAULT_KEY_SIZE),
            prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD)
        );
        return !sig.equals(appliedConfigSig);
    }

    private String configSignature(String theme, String enabledRaw, boolean haptic,
                                     String size, String voiceMode) {
        return theme + "|" + enabledRaw + "|" + haptic + "|" + size + "|" + voiceMode;
    }

    private View buildInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themeBg);
        root.addView(buildVoiceStrip(), matchWidthWrap());
        keyboardPanel = buildKeyboardPanel();
        root.addView(keyboardPanel, matchWidthWrap());
        applyVoiceOnlyVisibility();
        applyNavigationBarPadding(root);
        root.post(this::applyMicStripWidth);
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
        finalizePendingComposing();
        stopAllVoiceActivity(true);
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        finalizePendingComposing();
        stopAllVoiceActivity(true);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        dismissGlobePopup();
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
        finalizePendingComposing();
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
        strip.setPadding(dp(4), dp(4), dp(4), dp(4));

        // Fixed-width icons so BN (with ABC/kar) matches EN/AR sizes.
        strip.addView(compactActionKey("All", () -> {
            finalizePendingComposing();
            selectAllText();
        }), stripFixedLp(dp(44)));

        ImageButton stripUndoButton = stripIconButton(R.drawable.ic_undo);
        stripUndoButton.setOnClickListener(v -> {
            performKeyHaptic(v);
            undoText();
        });
        strip.addView(stripUndoButton, stripFixedLp(dp(STRIP_ICON_W)));

        expandArrowButton = iconButton(R.drawable.ic_arrow_up);
        expandArrowButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        expandArrowButton.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleKeyboardPanel();
        });
        strip.addView(expandArrowButton, stripFixedLp(dp(STRIP_ICON_W)));
        updateExpandButtonIcon();

        stripSettingsButton = stripIconButton(R.drawable.ic_settings);
        stripSettingsButton.setOnClickListener(v -> {
            performKeyHaptic(v);
            openKeyboardSettings();
        });
        strip.addView(stripSettingsButton, stripFixedLp(dp(STRIP_ICON_W)));

        karToggleKey = compactIconKey(R.drawable.ic_kar_toggle, this::toggleBnVowelRow);
        karToggleKey.setMinimumHeight(dp(STRIP_HEIGHT));
        strip.addView(karToggleKey, stripFixedLp(dp(STRIP_ICON_W)));

        stripPresetButton = stripIconButton(R.drawable.ic_presets);
        stripPresetButton.setOnClickListener(v -> {
            performKeyHaptic(v);
            togglePresetPopup(v);
        });
        strip.addView(stripPresetButton, stripFixedLp(dp(STRIP_ICON_W)));

        stripDeleteKey = createDeleteKeyView(STRIP_HEIGHT);
        strip.addView(stripDeleteKey, stripFixedLp(dp(STRIP_ICON_W)));

        View spacer = new View(this);
        strip.addView(spacer, weighted(STRIP_HEIGHT, 1f));

        stripMicKeyWrap = buildMicKeyView();
        strip.addView(stripMicKeyWrap, stripFixedLp(dp(STRIP_MIC_W)));

        updateKarToggleVisibility();
        return strip;
    }

    private void openKeyboardSettings() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            // Separate settings task: Back closes settings and returns to the app you were typing in.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            intent.putExtra(MainActivity.EXTRA_FROM_IME, true);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams stripFixedLp(int widthPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthPx, dp(STRIP_HEIGHT));
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private View buildMicKeyView() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setMinimumHeight(dp(STRIP_HEIGHT));
        wrap.setClickable(true);
        wrap.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleVoiceInput();
        });
        wrap.setOnLongClickListener(v -> {
            if (isRecording || isLiveActive || isTranscribing) return false;
            finalizePendingComposing();
            toggleVoiceInputMode();
            return true;
        });

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(dp(3), dp(2), dp(3), dp(1));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER);

        micModeIcon = new ImageView(this);
        micModeIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iconLp.setMargins(0, 0, dp(3), 0);
        topRow.addView(micModeIcon, iconLp);

        micLabel = new TextView(this);
        micLabel.setTextSize(12);
        micLabel.setTypeface(Typeface.DEFAULT_BOLD);
        micLabel.setSingleLine(true);
        micLabel.setEllipsize(TextUtils.TruncateAt.END);
        micLabel.setGravity(Gravity.CENTER);
        topRow.addView(micLabel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        micHoldHint = new TextView(this);
        micHoldHint.setText("⇄ mode");
        micHoldHint.setTextSize(8);
        micHoldHint.setTextColor(themeMuted);
        micHoldHint.setGravity(Gravity.CENTER);
        micHoldHint.setSingleLine(true);

        inner.addView(topRow, matchWidthWrap());
        inner.addView(micHoldHint, matchWidthWrap());

        wrap.addView(inner, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        stripMicKeyWrap = wrap;
        updateMicButtonAppearance();
        return wrap;
    }

    private LinearLayout buildKeyboardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), 0, dp(4), dp(4));

        presetComposeBar = buildPresetComposeBar();
        presetComposeBar.setVisibility(View.GONE);
        panel.addView(presetComposeBar, matchWidthWrap());

        suggestionBar = new LinearLayout(this);
        suggestionBar.setOrientation(LinearLayout.HORIZONTAL);
        suggestionBar.setGravity(Gravity.CENTER_VERTICAL);
        suggestionBar.setPadding(dp(2), dp(2), dp(2), dp(2));
        suggestionBar.setVisibility(View.GONE);
        buildSuggestionViews();
        panel.addView(suggestionBar, matchWidthWrap());

        letterContainer = new LinearLayout(this);
        letterContainer.setOrientation(LinearLayout.VERTICAL);
        panel.addView(letterContainer, matchWidthWrap());
        rebuildLetterRows();

        LinearLayout bottomRow = buildBottomRow();
        panel.addView(bottomRow, matchWidthWrap());
        updateKarToggleVisibility();
        refreshBottomRowForLanguage();

        return panel;
    }

    private String spaceLanguageLabel(String lang) {
        if (MainActivity.LANG_EN.equals(lang)) return "English";
        if (MainActivity.LANG_AR.equals(lang)) return "العربية";
        return "বাংলা";
    }

    private void cycleLayoutLanguage() {
        if (enabledLangs == null || enabledLangs.isEmpty()) return;
        finalizePendingComposing();
        int idx = enabledLangs.indexOf(layoutLang);
        if (idx < 0) idx = 0;
        String next = enabledLangs.get((idx + 1) % enabledLangs.size());
        if (next.equals(layoutLang) && enabledLangs.size() <= 1) {
            return;
        }
        setLayoutLanguage(next);
    }

    private LinearLayout buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(2), 0, 0);

        utilityMoreKey = createMoreKeyView(bottomKeyHeightDp);
        row.addView(utilityMoreKey, weighted(bottomKeyHeightDp, 0.7f));

        Button leftPunct = actionKey(",", () -> {
            finalizePendingComposing();
            commitText(", ");
        }, bottomKeyHeightDp);
        row.addView(leftPunct, weighted(bottomKeyHeightDp, 0.5f));

        ImageButton bottomGlobe = iconButton(R.drawable.ic_globe);
        styleKeyBackground(bottomGlobe, false);
        bottomGlobe.setOnClickListener(v -> {
            performKeyHaptic(v);
            showGlobeMenu(v);
        });
        row.addView(bottomGlobe, weighted(bottomKeyHeightDp, 0.55f));

        spaceKeyButton = new Button(this);
        spaceKeyButton.setText(spaceLanguageLabel(layoutLang));
        spaceKeyButton.setAllCaps(false);
        spaceKeyButton.setTextSize(11);
        spaceKeyButton.setMinWidth(0);
        spaceKeyButton.setMinHeight(0);
        spaceKeyButton.setPadding(0, 0, 0, 0);
        styleKeyButton(spaceKeyButton, false);
        configureSpaceKey(spaceKeyButton);
        // Slightly smaller space so globe fits beside it.
        row.addView(spaceKeyButton, weighted(bottomKeyHeightDp, 2.7f));

        boolean dari = MainActivity.LANG_BN.equals(layoutLang);
        rightPunctButton = actionKey(dari ? "\u0964" : ".", () -> {
            finalizePendingComposing();
            boolean useDari = MainActivity.LANG_BN.equals(layoutLang);
            commitText(useDari ? "\u0964" : ".");
        }, bottomKeyHeightDp);
        row.addView(rightPunctButton, weighted(bottomKeyHeightDp, 0.5f));

        enterKeyButton = new Button(this);
        enterKeyButton.setAllCaps(false);
        enterKeyButton.setTextSize(11);
        enterKeyButton.setMinWidth(0);
        enterKeyButton.setMinHeight(0);
        enterKeyButton.setPadding(dp(1), 0, dp(1), 0);
        styleKeyButton(enterKeyButton, true);
        enterKeyButton.setOnClickListener(v -> {
            performKeyHaptic(v);
            performSmartEnter();
        });
        row.addView(enterKeyButton, weighted(bottomKeyHeightDp, 0.85f));
        refreshEnterKeyLabel();

        return row;
    }

    private void configureSpaceKey(Button space) {
        final float[] startX = {0f};
        final float[] startY = {0f};
        final boolean[] cursorSwipe = {false};
        final boolean[] langSwipe = {false};
        space.setOnTouchListener((v, event) -> {
            int stepPx = dp(16);
            int langSwipePx = dp(28);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getRawX();
                    startY[0] = event.getRawY();
                    cursorSwipe[0] = false;
                    langSwipe[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX[0];
                    float dy = event.getRawY() - startY[0];
                    // Swipe up on space → cycle language (Gboard-like).
                    if (!cursorSwipe[0] && !langSwipe[0] && dy < -langSwipePx && Math.abs(dy) > Math.abs(dx)) {
                        langSwipe[0] = true;
                        performKeyHaptic(v);
                        cycleLayoutLanguage();
                        return true;
                    }
                    if (!langSwipe[0] && Math.abs(dx) >= stepPx && Math.abs(dx) > Math.abs(dy)) {
                        int steps = (int) (dx / stepPx);
                        moveCursorBy(steps);
                        startX[0] += steps * stepPx;
                        cursorSwipe[0] = true;
                        performKeyHaptic(v);
                        return true;
                    }
                    return cursorSwipe[0] || langSwipe[0];
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean consumed = cursorSwipe[0] || langSwipe[0];
                    cursorSwipe[0] = false;
                    langSwipe[0] = false;
                    return consumed;
                }
                default:
                    return false;
            }
        });
        space.setOnClickListener(v -> handleSpaceTap(v));
        space.setOnLongClickListener(v -> {
            performKeyHaptic(v);
            cycleLayoutLanguage();
            return true;
        });
    }

    private void moveCursorBy(int deltaChars) {
        if (presetComposeActive && presetComposeInput != null) {
            int pos = presetComposeInput.getSelectionStart();
            int newPos = Math.max(0, Math.min(presetComposeInput.getText().length(), pos + deltaChars));
            presetComposeInput.setSelection(newPos);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) return;
        int cursor = et.startOffset + et.selectionStart;
        int newPos = cursor + deltaChars;
        int min = et.startOffset;
        int max = et.startOffset + et.text.length();
        if (newPos < min) newPos = min;
        if (newPos > max) newPos = max;
        ic.setSelection(newPos, newPos);
    }

    private void handleSpaceTap(View source) {
        performKeyHaptic(source);
        long now = System.currentTimeMillis();
        if (!presetComposeActive && lastKeyWasSpace && (now - lastSpaceCommitMs) <= 500) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(2, 0);
                if (before != null && before.length() == 2 && before.charAt(1) == ' '
                    && Character.isLetterOrDigit(before.charAt(0))) {
                    ic.deleteSurroundingText(1, 0);
                    boolean dari = MainActivity.LANG_BN.equals(layoutLang);
                    commitText(dari ? "\u0964 " : ". ");
                    lastKeyWasSpace = false;
                    lastSpaceCommitMs = 0;
                    return;
                }
            }
        }
        commitText(" ");
        lastKeyWasSpace = true;
        lastSpaceCommitMs = now;
    }

    private void rebuildLetterRows() {
        if (letterContainer == null) return;
        dismissSymbolPopup();
        letterContainer.removeAllViews();
        bnRow1Layout = null;
        qwertyLetterButtons.clear();
        shiftKeyView = null;
        shiftState = 0;
        boolean qwerty = isRomanQwertyMode() && !morePage;
        String[][] rows = morePage ? moreRowsFor(layoutLang) : layoutRowsFor(layoutLang);
        for (int i = 0; i < rows.length; i++) {
            if (!morePage && i == 0 && bnDynamicRow1Enabled()) {
                bnRow1Layout = buildDynamicKeyRow(row1Keys(), true, false);
                letterContainer.addView(bnRow1Layout, matchWidthWrap());
            } else {
                addTextRow(letterContainer, rows[i], false, qwerty);
            }
        }
        updateKarToggleVisibility();
        refreshShiftVisuals();
        refreshBottomRowForLanguage();
        updateMoreKeyIcon();
    }

    private void refreshBottomRowForLanguage() {
        boolean dari = MainActivity.LANG_BN.equals(layoutLang);
        if (rightPunctButton != null) rightPunctButton.setText(dari ? "\u0964" : ".");
        if (spaceKeyButton != null) spaceKeyButton.setText(spaceLanguageLabel(layoutLang));
        updateKarToggleVisibility();
    }

    private boolean isRomanQwertyMode() {
        return MainActivity.LANG_EN.equals(layoutLang);
    }

    private boolean bnDynamicRow1Enabled() {
        return MainActivity.LANG_BN.equals(layoutLang) && !morePage;
    }

    private String[] row1Keys() {
        if (!bnRow1Vowels && bnKarBase != null) {
            return buildBnKarKeys(bnKarBase);
        }
        return BN_VOWEL_ROW;
    }

    private String[] buildBnKarKeys(String consonant) {
        String[] keys = new String[BN_KAR_SUFFIXES.length];
        for (int i = 0; i < BN_KAR_SUFFIXES.length; i++) {
            keys[i] = consonant + BN_KAR_SUFFIXES[i];
        }
        return keys;
    }

    private void resetBnKarState() {
        bnKarBase = null;
        bnRow1Vowels = true;
    }

    private void toggleBnVowelRow() {
        if (!bnDynamicRow1Enabled()) return;
        if (bnRow1Vowels) {
            if (bnKarBase == null) return;
            bnRow1Vowels = false;
        } else {
            bnRow1Vowels = true;
        }
        refreshBnRow1();
    }

    private void refreshBnRow1() {
        if (bnRow1Layout == null) return;
        bnRow1Layout.removeAllViews();
        populateKeyRow(bnRow1Layout, row1Keys(), true, false);
        updateKarToggleHighlight();
    }

    private void updateKarToggleVisibility() {
        if (karToggleKey == null) return;
        karToggleKey.setVisibility(bnDynamicRow1Enabled() ? View.VISIBLE : View.GONE);
        updateKarToggleHighlight();
    }

    private void updateKarToggleHighlight() {
        if (karToggleKey == null || !bnDynamicRow1Enabled()) return;
        styleKeyBackground(karToggleKey, !bnRow1Vowels);
    }

    private void onConsonantTyped(String consonant) {
        if (!bnDynamicRow1Enabled()) return;
        bnKarBase = consonant;
        bnRow1Vowels = false;
        refreshBnRow1();
    }

    private void onRow1KeyPressed(String key) {
        if (bnDynamicRow1Enabled() && !bnRow1Vowels && bnKarBase != null
            && key.length() > bnKarBase.length() && key.startsWith(bnKarBase)) {
            deleteBackward();
            commitText(key);
            return;
        }
        commitText(key);
    }

    private void onLetterKeyPressed(String key) {
        commitText(key);
        if (isBnKarTrigger(key)) {
            onConsonantTyped(key);
        }
    }

    private void onQwertyLetterPressed(Button button) {
        String label = button.getText().toString();
        commitText(label);
        if (shiftState == 1) {
            shiftState = 0;
            refreshShiftVisuals();
        }
    }

    private boolean isBnKarTrigger(String key) {
        if (key == null || key.length() != 1) return false;
        char c = key.charAt(0);
        if (c >= '\u0985' && c <= '\u0994') return false;
        return (c >= '\u0995' && c <= '\u09B9') || c == '\u09DC' || c == '\u09DD' || c == '\u09CE';
    }

    private void finalizePendingComposing() {
        // No-op: phonetic composing removed.
    }

    // ---------- Word suggestions ----------

    private void loadBnDictionaryIfNeeded() {
        if (bnDictionary != null || bnDictionaryLoadStarted) return;
        bnDictionaryLoadStarted = true;
        executor.execute(() -> {
            try {
                BanglaDictionary dict = BanglaDictionary.load(this);
                mainHandler.post(() -> {
                    bnDictionary = dict;
                    refreshSuggestions();
                });
            } catch (Exception e) {
                mainHandler.post(() -> bnDictionaryLoadStarted = false);
            }
        });
    }

    private void buildSuggestionViews() {
        suggestionBar.removeAllViews();
        for (int i = 0; i < suggestionViews.length; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(themeKeyStroke);
                suggestionBar.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(18)));
            }
            TextView tv = new TextView(this);
            tv.setTextSize(15);
            tv.setTextColor(themeKeyText);
            if (i == 1) tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(4), 0, dp(4), 0);
            tv.setOnClickListener(v -> {
                CharSequence word = ((TextView) v).getText();
                if (word.length() > 0) {
                    performKeyHaptic(v);
                    applySuggestion(word.toString());
                }
            });
            suggestionBar.addView(tv, new LinearLayout.LayoutParams(0, dp(34), 1f));
            suggestionViews[i] = tv;
        }
    }

    private void refreshSuggestions() {
        if (suggestionBar == null) return;
        BanglaDictionary dict = bnDictionary;
        boolean available = dict != null && MainActivity.LANG_BN.equals(layoutLang)
            && !presetComposeActive;
        suggestionBar.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) return;
        java.util.List<String> words = dict.suggest(currentBnPartialWord(), suggestionViews.length);
        for (int i = 0; i < suggestionViews.length; i++) {
            suggestionViews[SUGGESTION_SLOT_ORDER[i]].setText(i < words.size() ? words.get(i) : "");
        }
    }

    /** Bengali word fragment immediately before the cursor, or "" when mid-word/selection. */
    private String currentBnPartialWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) return "";
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0 && isBnWordChar(after.charAt(0))) return "";
        CharSequence before = ic.getTextBeforeCursor(32, 0);
        if (before == null) return "";
        int i = before.length();
        while (i > 0 && isBnWordChar(before.charAt(i - 1))) i--;
        return before.subSequence(i, before.length()).toString();
    }

    private boolean isBnWordChar(char c) {
        if (c >= '\u09E6' && c <= '\u09EF') return false; // Bengali digits end a word
        return (c >= '\u0980' && c <= '\u09FF') || c == '\u200C' || c == '\u200D';
    }

    private void applySuggestion(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String partial = currentBnPartialWord();
        if (partial.isEmpty()) return;
        ic.deleteSurroundingText(partial.length(), 0);
        commitText(word + " ");
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd);
        refreshSuggestions();
    }

    // ---------- Shift / caps lock ----------

    private boolean shiftActive() {
        return shiftState != 0;
    }

    private Button buildShiftKeyView() {
        Button b = new Button(this);
        b.setText(shiftState == 2 ? "\u21EA" : "\u21E7");
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        styleKeyButton(b, shiftState != 0);
        b.setOnClickListener(v -> {
            performKeyHaptic(v);
            cycleShift();
        });
        shiftKeyView = b;
        return b;
    }

    private void cycleShift() {
        if (shiftState == 0) shiftState = 1;
        else if (shiftState == 1) shiftState = 2;
        else shiftState = 0;
        refreshShiftVisuals();
    }

    private void refreshShiftVisuals() {
        if (shiftKeyView != null) {
            shiftKeyView.setText(shiftState == 2 ? "\u21EA" : "\u21E7");
            styleKeyButton(shiftKeyView, shiftState != 0);
        }
        for (Button b : qwertyLetterButtons) {
            Object tag = b.getTag();
            if (tag instanceof String) {
                String base = (String) tag;
                b.setText(shiftActive() ? base.toUpperCase(java.util.Locale.US) : base);
            }
        }
    }

    private String[][] moreRowsFor(String lang) {
        // No separate Back key — bottom-left more key toggles back to page 1.
        // Keep delete on the last row (same corner as page 1).
        String[][] nums = numberRowsFor(lang);
        if (MainActivity.LANG_BN.equals(lang)) {
            return new String[][]{
                nums[0],
                nums[1],
                nums[2],
                {"\u09CC", "\u09C8", "\u09C3", "\u09C2", "\u0982", "\u0983", "\u0981", "\u09CE", "\u2018", "\u2019"},
                {"\u09E0", "\u098B", "\u098C", "\u09F3", "\u20AC", "\u0965", "~", "`", "|", TOK_DELETE},
            };
        }
        return new String[][]{
            nums[0],
            nums[1],
            nums[2],
            {"<", ">", "[", "]", "{", "}", "^", "~", "`", TOK_DELETE},
        };
    }

    private String[][] numberRowsFor(String lang) {
        String[] symbolsA = {"@", "#", "$", "%", "&", "*", "-", "+", "(", ")"};
        String[] symbolsB = {"!", "\"", "'", ":", ";", "/", "?", "_", ".", "="};
        if (MainActivity.LANG_BN.equals(lang)) {
            return new String[][]{
                {"\u09E7", "\u09E8", "\u09E9", "\u09EA", "\u09EB", "\u09EC", "\u09ED", "\u09EE", "\u09EF", "\u09E6"},
                symbolsA,
                {"\u09F3", "\u20AC", "<", ">", "[", "]", "{", "}", "\u0964", "="},
            };
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            return new String[][]{
                {"\u0661", "\u0662", "\u0663", "\u0664", "\u0665", "\u0666", "\u0667", "\u0668", "\u0669", "\u0660"},
                symbolsA,
                symbolsB,
            };
        }
        return new String[][]{
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            symbolsA,
            symbolsB,
        };
    }

    private String[][] layoutRowsFor(String lang) {
        if (isRomanQwertyMode()) {
            return new String[][]{
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
                {TOK_SHIFT, "z", "x", "c", "v", "b", "n", "m", TOK_DELETE},
            };
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            return new String[][]{
                {"\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062D"},
                {"\u062C", "\u0634", "\u0633", "\u064A", "\u0628", "\u0644", "\u0627", "\u062A", "\u0646", "\u0645"},
                {"\u0643", "\u0637", "\u0630", "\u0621", "\u0624", "\u0631", "\u0649", "\u0629", "\u0648", TOK_DELETE},
            };
        }
        return new String[][]{
            {"\u0985", "\u0986", "\u0987", "\u0988", "\u0989", "\u098A", "\u098F", "\u0990", "\u0993", "\u0994"},
            {"\u0995", "\u0996", "\u0997", "\u0998", "\u0999", "\u099A", "\u099B", "\u099C", "\u099D", "\u099E"},
            {"\u099F", "\u09A0", "\u09A1", "\u09A2", "\u09A3", "\u09A4", "\u09A5", "\u09A6", "\u09A7", "\u09A8"},
            {"\u09AA", "\u09AB", "\u09AC", "\u09AD", "\u09AE", "\u09AF", "\u09B0", "\u09B2", "\u09B6", "\u09B7"},
            {"\u09B8", "\u09B9", "\u09DC", "\u09DD", "\u09CD\u09AF", "\u09AF\u09BC", ",", "\u09CD", "\u0964", TOK_DELETE},
        };
    }

    private PopupWindow globePopup;
    private PopupWindow symbolPopup;
    private PopupWindow presetPopup;

    private void toggleMorePage() {
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
        finalizePendingComposing();
        morePage = !morePage;
        resetBnKarState();
        rebuildLetterRows();
        updateMoreKeyIcon();
    }

    private void updateMoreKeyIcon() {
        if (morePageIcon == null) return;
        morePageIcon.setImageResource(morePage ? R.drawable.ic_chevron_left : R.drawable.ic_chevron_right);
        if (utilityMoreKey != null) {
            styleKeyBackground(utilityMoreKey, true);
        }
    }

    private java.util.List<String> loadPresets() {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            String raw = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_PRESETS, MainActivity.DEFAULT_PRESETS);
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i, "").trim();
                if (!item.isEmpty()) list.add(item);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void savePresets(java.util.List<String> presets) {
        JSONArray arr = new JSONArray();
        for (String preset : presets) {
            if (preset != null && !preset.trim().isEmpty()) {
                arr.put(preset.trim());
            }
        }
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_PRESETS, arr.toString())
            .apply();
    }

    private LinearLayout buildPresetComposeBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(4));

        presetComposeInput = new EditText(this);
        presetComposeInput.setHint("Type new preset…");
        presetComposeInput.setTextSize(13);
        presetComposeInput.setSingleLine(false);
        presetComposeInput.setMaxLines(3);
        presetComposeInput.setMinHeight(dp(34));
        presetComposeInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(themeKeyBg);
        inputBg.setCornerRadius(dp(7));
        inputBg.setStroke(dp(1), themeAccent);
        presetComposeInput.setBackground(inputBg);
        presetComposeInput.setTextColor(themeKeyText);
        presetComposeInput.setHintTextColor(themeMuted);
        row.addView(presetComposeInput, weighted(UTILITY_KEY_HEIGHT, 1f));

        Button saveBtn = new Button(this);
        saveBtn.setText("Save");
        saveBtn.setAllCaps(false);
        saveBtn.setTextSize(11);
        saveBtn.setMinWidth(0);
        saveBtn.setMinHeight(0);
        saveBtn.setPadding(dp(6), dp(2), dp(6), dp(2));
        styleKeyButton(saveBtn, true);
        saveBtn.setOnClickListener(v -> savePresetCompose());
        row.addView(saveBtn, weighted(UTILITY_KEY_HEIGHT, 0.42f));

        Button cancelBtn = new Button(this);
        cancelBtn.setText("✕");
        cancelBtn.setAllCaps(false);
        cancelBtn.setTextSize(12);
        cancelBtn.setMinWidth(0);
        cancelBtn.setMinHeight(0);
        cancelBtn.setPadding(dp(4), dp(2), dp(4), dp(2));
        styleKeyButton(cancelBtn, false);
        cancelBtn.setOnClickListener(v -> hidePresetCompose());
        row.addView(cancelBtn, weighted(UTILITY_KEY_HEIGHT, 0.28f));
        return row;
    }

    private void showPresetCompose() {
        finalizePendingComposing();
        if (voiceOnlyMode) {
            voiceOnlyMode = false;
            applyVoiceOnlyVisibility();
            savePreferences();
        }
        if (presetComposeBar == null) return;
        presetComposeBar.setVisibility(View.VISIBLE);
        presetComposeActive = true;
        presetComposeInput.setText("");
        presetComposeInput.requestFocus();
        refreshSuggestions();
    }

    private void hidePresetCompose() {
        presetComposeActive = false;
        if (presetComposeBar != null) {
            presetComposeBar.setVisibility(View.GONE);
        }
        if (presetComposeInput != null) {
            presetComposeInput.setText("");
        }
        refreshSuggestions();
    }

    private void savePresetCompose() {
        if (presetComposeInput == null) return;
        String text = presetComposeInput.getText().toString().trim();
        if (text.isEmpty()) return;
        java.util.List<String> updated = loadPresets();
        if (updated.size() >= MAX_PRESETS) return;
        updated.add(text);
        savePresets(updated);
        hidePresetCompose();
    }

    private void dismissPresetPopup() {
        if (presetPopup != null && presetPopup.isShowing()) {
            presetPopup.dismiss();
        }
        presetPopup = null;
    }

    private void togglePresetPopup(View anchor) {
        if (presetPopup != null && presetPopup.isShowing()) {
            dismissPresetPopup();
            return;
        }
        finalizePendingComposing();
        dismissGlobePopup();
        dismissSymbolPopup();
        hidePresetCompose();
        showPresetPopup(anchor);
    }

    private void showPresetPopup(View anchor) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(themeKeyBg);
        menuBg.setCornerRadius(dp(10));
        menuBg.setStroke(dp(1), themeKeyStroke);
        menu.setBackground(menuBg);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        int menuWidth = dp(260);

        TextView title = new TextView(this);
        title.setText("Preset messages");
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(themeMuted);
        title.setPadding(dp(4), 0, dp(4), dp(6));
        menu.addView(title, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        java.util.List<String> presets = loadPresets();
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (presets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No presets yet");
            empty.setTextSize(13);
            empty.setTextColor(themeMuted);
            empty.setPadding(dp(8), dp(4), dp(8), dp(8));
            list.addView(empty, matchWidthWrap());
        } else {
            for (int i = 0; i < presets.size(); i++) {
                final int index = i;
                final String message = presets.get(i);
                Button item = new Button(this);
                item.setText(message);
                item.setAllCaps(false);
                item.setTextSize(14);
                item.setMinWidth(0);
                item.setMinHeight(0);
                item.setPadding(dp(10), dp(8), dp(10), dp(8));
                item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                item.setSingleLine(true);
                item.setEllipsize(TextUtils.TruncateAt.END);
                styleKeyButton(item, false);
                item.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText(message);
                    dismissPresetPopup();
                });
                item.setOnLongClickListener(v -> {
                    java.util.List<String> updated = loadPresets();
                    if (index >= 0 && index < updated.size()) {
                        updated.remove(index);
                        savePresets(updated);
                        dismissPresetPopup();
                        showPresetPopup(anchor);
                    }
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                lp.bottomMargin = dp(4);
                list.addView(item, lp);
            }
        }
        scroll.addView(list, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(Math.min(180, Math.max(48, presets.size() * 44)))
        );
        menu.addView(scroll, scrollLp);

        View divider = new View(this);
        divider.setBackgroundColor(themeKeyStroke);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divParams.setMargins(0, dp(6), 0, dp(6));
        menu.addView(divider, divParams);

        Button addBtn = new Button(this);
        addBtn.setText("+ Add new message");
        addBtn.setAllCaps(false);
        addBtn.setTextSize(14);
        addBtn.setMinWidth(0);
        addBtn.setMinHeight(0);
        addBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        addBtn.setGravity(Gravity.CENTER);
        styleKeyButton(addBtn, true);
        addBtn.setOnClickListener(v -> {
            dismissPresetPopup();
            showPresetCompose();
        });
        menu.addView(addBtn, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int menuHeight = menu.getMeasuredHeight();

        presetPopup = new PopupWindow(menu, menuWidth, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        presetPopup.setElevation(dp(10));
        presetPopup.setBackgroundDrawable(null);
        presetPopup.setOutsideTouchable(true);
        presetPopup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuHeight + dp(6)));
    }

    private void showGlobeMenu(View anchor) {
        finalizePendingComposing();
        dismissPresetPopup();
        dismissSymbolPopup();
        dismissGlobePopup();
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(themeKeyBg);
        menuBg.setCornerRadius(dp(10));
        menuBg.setStroke(dp(1), themeKeyStroke);
        menu.setBackground(menuBg);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        int menuWidth = dp(216);

        for (String lang : enabledLangs) {
            Button item = new Button(this);
            item.setText(languageMenuLabel(lang));
            item.setAllCaps(false);
            item.setTextSize(15);
            item.setMinWidth(0);
            item.setMinHeight(0);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            styleKeyButton(item, layoutLang.equals(lang));
            item.setOnClickListener(v -> {
                setLayoutLanguage(lang);
                dismissGlobePopup();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(4);
            menu.addView(item, lp);
        }

        View divider = new View(this);
        divider.setBackgroundColor(themeKeyStroke);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divParams.setMargins(0, dp(4), 0, dp(8));
        menu.addView(divider, divParams);

        Button otherKeyboard = new Button(this);
        otherKeyboard.setText("Other keyboard…");
        otherKeyboard.setAllCaps(false);
        otherKeyboard.setTextSize(14);
        otherKeyboard.setMinWidth(0);
        otherKeyboard.setMinHeight(0);
        otherKeyboard.setPadding(dp(12), dp(10), dp(12), dp(10));
        otherKeyboard.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        styleKeyButton(otherKeyboard, false);
        otherKeyboard.setOnClickListener(v -> {
            dismissGlobePopup();
            switchToNextInputMethod(false);
        });
        menu.addView(otherKeyboard, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int menuHeight = menu.getMeasuredHeight();

        globePopup = new PopupWindow(menu, menuWidth, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        globePopup.setElevation(dp(10));
        globePopup.setBackgroundDrawable(null);
        globePopup.setOutsideTouchable(true);
        globePopup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuHeight + dp(6)));
    }

    private void dismissGlobePopup() {
        if (globePopup != null && globePopup.isShowing()) {
            globePopup.dismiss();
        }
        globePopup = null;
    }

    private void dismissSymbolPopup() {
        if (symbolPopup != null && symbolPopup.isShowing()) {
            symbolPopup.dismiss();
        }
        symbolPopup = null;
    }

    private void showSymbolPopup(View anchor, String[] symbols) {
        dismissSymbolPopup();
        dismissGlobePopup();
        dismissPresetPopup();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(themeKeyBg);
        panelBg.setCornerRadius(dp(10));
        panelBg.setStroke(dp(1), themeKeyStroke);
        panel.setBackground(panelBg);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));

        int cols = 5;
        int cellSize = dp(40);
        for (int i = 0; i < symbols.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int j = i; j < Math.min(i + cols, symbols.length); j++) {
                String sym = symbols[j];
                Button btn = new Button(this);
                btn.setText(sym);
                btn.setAllCaps(false);
                btn.setTextSize(16);
                btn.setMinWidth(0);
                btn.setMinHeight(0);
                btn.setPadding(0, 0, 0, 0);
                styleKeyButton(btn, false);
                btn.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText(sym);
                    dismissSymbolPopup();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellSize, cellSize);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(btn, lp);
            }
            panel.addView(row, matchWidthWrap());
        }

        int popupWidth = dp(6 * 2 + cols * 44);
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int popupHeight = panel.getMeasuredHeight();

        symbolPopup = new PopupWindow(panel, popupWidth, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        symbolPopup.setElevation(dp(10));
        symbolPopup.setBackgroundDrawable(null);
        symbolPopup.setOutsideTouchable(true);
        symbolPopup.showAsDropDown(anchor, 0, -(anchor.getHeight() + popupHeight + dp(8)));
    }

    private String languageMenuLabel(String lang) {
        String name = languageLabel(lang);
        return layoutLang.equals(lang) ? name + "  ✓" : name;
    }

    private ImageButton iconButton(int drawableRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setBackgroundColor(0x00000000);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        return button;
    }

    private ImageButton stripIconButton(int drawableRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setBackgroundColor(0x00000000);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        return button;
    }

    private View compactIconKey(int drawableRes, Runnable action) {
        FrameLayout wrap = new FrameLayout(this);
        styleKeyBackground(wrap, false);
        ImageButton btn = iconButton(drawableRes);
        btn.setPadding(dp(6), dp(6), dp(6), dp(6));
        btn.setOnClickListener(v -> {
            performKeyHaptic(v);
            action.run();
        });
        wrap.addView(btn, matchParentSquare());
        wrap.setMinimumHeight(dp(STRIP_HEIGHT));
        return wrap;
    }

    private Button compactActionKey(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(10);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(1), 0, dp(1), 0);
        styleKeyButton(button, false);
        button.setOnClickListener(v -> {
            performKeyHaptic(v);
            action.run();
        });
        return button;
    }

    private View createMoreKeyView(int heightDp) {
        FrameLayout wrap = new FrameLayout(this);
        styleKeyBackground(wrap, true);
        morePageIcon = iconButton(morePage ? R.drawable.ic_chevron_left : R.drawable.ic_chevron_right);
        morePageIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
        morePageIcon.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleMorePage();
        });
        morePageIcon.setOnLongClickListener(v -> {
            if (MainActivity.LANG_BN.equals(layoutLang) && !morePage) {
                finalizePendingComposing();
                showSymbolPopup(morePageIcon, BN_DANDA_POPUP_SYMBOLS);
                return true;
            }
            return false;
        });
        wrap.addView(morePageIcon, matchParentSquare());
        wrap.setMinimumHeight(dp(heightDp));
        return wrap;
    }

    private View createDeleteKeyView(int heightDp) {
        FrameLayout wrap = new FrameLayout(this);
        styleKeyBackground(wrap, false);
        ImageButton delete = iconButton(R.drawable.ic_close_circle);
        delete.setPadding(dp(4), dp(4), dp(4), dp(4));
        configureDeleteButton(delete);
        wrap.addView(delete, matchParentSquare());
        wrap.setMinimumHeight(dp(heightDp));
        return wrap;
    }

    private Drawable buildKeyBackground(boolean accent) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(themeKeyBg);
        normal.setCornerRadius(dp(7));
        normal.setStroke(dp(1), accent ? themeAccent : themeKeyStroke);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(darkenColor(themeKeyBg));
        pressed.setCornerRadius(dp(7));
        pressed.setStroke(dp(1), accent ? themeAccent : themeKeyStroke);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private int darkenColor(int color) {
        int a = Color.alpha(color);
        int r = (int) (Color.red(color) * 0.82f);
        int g = (int) (Color.green(color) * 0.82f);
        int b = (int) (Color.blue(color) * 0.82f);
        return Color.argb(a, r, g, b);
    }

    private void styleKeyBackground(View view, boolean accent) {
        view.setBackground(buildKeyBackground(accent));
    }

    private Button actionKey(String label, Runnable action, int heightDp) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        styleKeyButton(button, false);
        button.setOnClickListener(v -> {
            performKeyHaptic(v);
            action.run();
        });
        return button;
    }

    private void setLayoutLanguage(String lang) {
        finalizePendingComposing();
        layoutLang = lang;
        morePage = false;
        resetBnKarState();
        rebuildLetterRows();
        savePreferences();
        refreshSuggestions();
    }

    private void toggleKeyboardPanel() {
        finalizePendingComposing();
        voiceOnlyMode = !voiceOnlyMode;
        applyVoiceOnlyVisibility();
        savePreferences();
    }

    private void applyVoiceOnlyVisibility() {
        if (keyboardPanel != null) {
            keyboardPanel.setVisibility(voiceOnlyMode ? View.GONE : View.VISIBLE);
        }
        // Delete stays on the strip only when the letter keyboard is folded.
        if (stripDeleteKey != null) {
            stripDeleteKey.setVisibility(voiceOnlyMode ? View.VISIBLE : View.GONE);
        }
        if (voiceOnlyMode) {
            dismissSymbolPopup();
            dismissPresetPopup();
            hidePresetCompose();
            morePage = false;
            updateMoreKeyIcon();
        }
        updateExpandButtonIcon();
        applyMicStripWidth();
    }

    /** Mic ~2 letter-key widths; keep timer readable without crowding BN strip icons. */
    private void applyMicStripWidth() {
        if (stripMicKeyWrap == null) return;
        android.view.ViewGroup.LayoutParams raw = stripMicKeyWrap.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
        int target = dp(STRIP_MIC_W);
        if (lp.width == target && lp.weight == 0f) return;
        lp.width = target;
        lp.height = dp(STRIP_HEIGHT);
        lp.weight = 0f;
        stripMicKeyWrap.setLayoutParams(lp);
    }

    private void updateExpandButtonIcon() {
        if (expandArrowButton == null) return;
        expandArrowButton.setImageResource(voiceOnlyMode ? R.drawable.ic_arrow_up : R.drawable.ic_arrow_down);
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
        prefetchSonioxKeyIfNeeded();
        Toast.makeText(this,
            MainActivity.MODE_LIVE.equals(voiceInputMode)
                ? "Live transcription"
                : "Record then transcribe",
            Toast.LENGTH_SHORT).show();
    }

    private void toggleVoiceInput() {
        finalizePendingComposing();
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
        if (micLabel == null || stripMicKeyWrap == null) return;
        boolean isLiveMode = MainActivity.MODE_LIVE.equals(voiceInputMode);
        int accent = isLiveMode ? COLOR_LIVE : COLOR_RECORD;
        int softBg = isLiveMode ? COLOR_LIVE_SOFT : COLOR_RECORD_SOFT;
        int iconRes = isLiveMode ? R.drawable.ic_live : R.drawable.ic_mic;

        if (isTranscribing) {
            applyMicKeyStyle(COLOR_RECORD, COLOR_RECORD, "Transcribing…", R.drawable.ic_mic, true, false);
        } else if (isLiveConnecting) {
            applyMicKeyStyle(COLOR_LIVE, COLOR_LIVE, "...", R.drawable.ic_live, true, false);
        } else if (isLiveActive) {
            applyMicKeyStyle(COLOR_LIVE, COLOR_LIVE, formatVoiceElapsed(), R.drawable.ic_live, true, false);
        } else if (isRecording) {
            applyMicKeyStyle(COLOR_RECORD, COLOR_RECORD, formatVoiceElapsed(), R.drawable.ic_mic, true, false);
        } else {
            applyMicKeyStyle(softBg, accent, isLiveMode ? "Live" : "Record", iconRes, false, true);
        }
    }

    private void applyMicKeyStyle(int bgColor, int accentColor, String label, int iconRes,
                                  boolean active, boolean showHoldHint) {
        micLabel.setText(label);
        micModeIcon.setImageResource(iconRes);
        micModeIcon.setColorFilter(active ? COLOR_ACTIVE_TEXT : accentColor, PorterDuff.Mode.SRC_IN);
        micLabel.setTextColor(active ? COLOR_ACTIVE_TEXT : themeKeyText);
        micHoldHint.setVisibility(showHoldHint ? View.VISIBLE : View.GONE);
        if (showHoldHint) {
            micHoldHint.setText("⇄ mode");
            micHoldHint.setTextColor(themeMuted);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(BTN_CORNER_RADIUS_DP));
        bg.setColor(bgColor);
        if (!active) {
            bg.setStroke(dp(1), accentColor);
        }
        stripMicKeyWrap.setBackground(bg);
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

        liveTranscriber = new SonioxLiveTranscriber();
        liveTranscriber.start(this, endpoint, layoutLang, new SonioxLiveTranscriber.Listener() {
            @Override
            public void onConnecting() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = true;
                    updateMicButtonAppearance();
                });
            }

            @Override
            public void onListening() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = false;
                    startVoiceTimer();
                    updateMicButtonAppearance();
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
                    updateLiveInsert(VoicePunctuation.apply(finalText), partialText);
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
        } catch (Exception e) {
            cancelRecordingQuietly();
            postStatus("Mic error");
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
        isTranscribing = true;
        updateMicButtonAppearance();

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
                commitText(VoicePunctuation.apply(text));
            } else if (error != null) {
                postStatus(error);
            }
            updateMicButtonAppearance();
        });
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
        lastKeyWasSpace = false;
        if (presetComposeActive && presetComposeInput != null) {
            int start = presetComposeInput.getSelectionStart();
            int end = presetComposeInput.getSelectionEnd();
            if (start < 0) start = presetComposeInput.getText().length();
            if (end < 0) end = start;
            presetComposeInput.getText().replace(Math.min(start, end), Math.max(start, end), text);
            return;
        }
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.commitText(text, 1);
        refreshSuggestions();
    }

    private void deleteBackward() {
        if (presetComposeActive && presetComposeInput != null) {
            int start = presetComposeInput.getSelectionStart();
            int end = presetComposeInput.getSelectionEnd();
            if (start < 0 || end < 0) return;
            if (start != end) {
                presetComposeInput.getText().delete(Math.min(start, end), Math.max(start, end));
            } else if (start > 0) {
                presetComposeInput.getText().delete(start - 1, start);
            }
            return;
        }
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        CharSequence selectedText = inputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            inputConnection.commitText("", 1);
            return;
        }

        inputConnection.deleteSurroundingText(1, 0);
    }

    private void deleteWordBackward() {
        if (presetComposeActive && presetComposeInput != null) {
            int start = presetComposeInput.getSelectionStart();
            int end = presetComposeInput.getSelectionEnd();
            if (start < 0) start = presetComposeInput.getText().length();
            if (end < 0) end = start;
            if (start != end) {
                presetComposeInput.getText().delete(Math.min(start, end), Math.max(start, end));
                return;
            }
            String text = presetComposeInput.getText().toString();
            int i = start;
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
            if (i == start) i = Math.max(0, start - 1);
            presetComposeInput.getText().delete(i, start);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
            return;
        }

        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) {
            deleteBackward();
            return;
        }
        int len = before.length();
        int i = len;
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
        int count = len - i;
        if (count <= 0) count = 1;
        ic.deleteSurroundingText(count, 0);
    }

    private void performSingleDelete(View source) {
        performKeyHaptic(source);
        deleteBackward();
        lastKeyWasSpace = false;
        refreshSuggestions();
    }

    private void performWordDelete(View source) {
        performKeyHaptic(source);
        deleteWordBackward();
        lastKeyWasSpace = false;
        refreshSuggestions();
    }

    private void selectAllText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.selectAll);
    }

    private void undoText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.undo);
    }

    private void configureDeleteButton(View button) {
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] moved = {false};
        final boolean[] wordDeleted = {false};
        final boolean[] longFired = {false};
        final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                if (moved[0] || wordDeleted[0]) return;
                longFired[0] = true;
                mainHandler.removeCallbacks(repeatDeleteRunnable);
                performSingleDelete(button);
                mainHandler.postDelayed(repeatDeleteRunnable, 260);
            }
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    moved[0] = false;
                    wordDeleted[0] = false;
                    longFired[0] = false;
                    activeDeleteSource = button;
                    v.setPressed(true);
                    mainHandler.postDelayed(longPress, 350);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    if (!wordDeleted[0] && dx < -dp(40) && Math.abs(dy) < dp(28)) {
                        mainHandler.removeCallbacks(longPress);
                        mainHandler.removeCallbacks(repeatDeleteRunnable);
                        moved[0] = true;
                        wordDeleted[0] = true;
                        performWordDelete(button);
                        downX[0] = event.getRawX();
                    } else if (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12)) {
                        moved[0] = true;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    mainHandler.removeCallbacks(longPress);
                    mainHandler.removeCallbacks(repeatDeleteRunnable);
                    if (!moved[0] && !wordDeleted[0] && !longFired[0]) {
                        performSingleDelete(button);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    mainHandler.removeCallbacks(longPress);
                    mainHandler.removeCallbacks(repeatDeleteRunnable);
                    return true;
                default:
                    return false;
            }
        });
    }

    private LinearLayout buildDynamicKeyRow(String[] keys, boolean isRow1, boolean qwerty) {
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER);
        keyRow.setPadding(0, dp(2), 0, 0);
        populateKeyRow(keyRow, keys, isRow1, qwerty);
        return keyRow;
    }

    private void attachLongPressAlt(Button button, String key) {
        String base = key.toLowerCase(java.util.Locale.US);
        String[] alts = EN_LONG_PRESS.get(base);
        if (alts == null) alts = BN_LONG_PRESS.get(key);
        if (alts == null) return;
        final String[] altsFinal = alts;
        button.setOnLongClickListener(v -> {
            finalizePendingComposing();
            showSymbolPopup(button, altsFinal);
            return true;
        });
    }

    private void populateKeyRow(LinearLayout keyRow, String[] keys, boolean isRow1, boolean qwerty) {
        for (String key : keys) {
            if (TOK_DELETE.equals(key)) {
                View deleteKey = createDeleteKeyView(letterKeyHeightDp);
                keyRow.addView(deleteKey, weighted(letterKeyHeightDp, 1));
                continue;
            }
            if (TOK_BACK.equals(key)) {
                // Legacy sentinel — page toggle lives on the bottom-left more key.
                continue;
            }
            if (TOK_SHIFT.equals(key)) {
                keyRow.addView(buildShiftKeyView(), weighted(letterKeyHeightDp, 1f));
                continue;
            }
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(0, 0, 0, 0);
            String label = qwerty && shiftActive() ? key.toUpperCase(java.util.Locale.US) : key;
            button.setText(label);
            button.setTextSize(15);
            styleKeyButton(button, false);

            if (qwerty) {
                button.setTag(key);
                qwertyLetterButtons.add(button);
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    onQwertyLetterPressed(button);
                });
            } else if (",".equals(key)) {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText(", ");
                });
            } else if ("\u0964".equals(key)) {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText("\u0964");
                });
            } else if (isRow1) {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    onRow1KeyPressed(key);
                });
            } else {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    onLetterKeyPressed(key);
                });
            }
            attachLongPressAlt(button, key);
            keyRow.addView(button, weighted(letterKeyHeightDp, 1));
        }
    }

    private void addTextRow(LinearLayout root, String[] keys, boolean isRow1, boolean qwerty) {
        LinearLayout keyRow = buildDynamicKeyRow(keys, isRow1, qwerty);
        root.addView(keyRow, matchWidthWrap());
    }

    private void styleKeyButton(Button button, boolean accent) {
        button.setBackground(buildKeyBackground(accent));
        button.setTextColor(accent ? themeAccent : themeKeyText);
    }

    private void performKeyHaptic(View v) {
        if (v == null || !hapticEnabled) return;
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // ---------- Smart Enter ----------

    private void refreshEnterKeyLabel() {
        if (enterKeyButton == null) return;
        enterKeyButton.setText(smartEnterLabel());
    }

    private String smartEnterLabel() {
        if (currentEditorInfo == null) return "Enter";
        boolean noEnter = (currentEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (noEnter) return "Enter";
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_DONE: return "Done";
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            default: return "Enter";
        }
    }

    private void performSmartEnter() {
        if (presetComposeActive) {
            commitText("\n");
            return;
        }
        finalizePendingComposing();
        if (currentEditorInfo != null) {
            boolean noEnter = (currentEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
            if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.performEditorAction(action);
                    return;
                }
            }
        }
        commitText("\n");
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
        finalizePendingComposing();
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        MainActivity.applyComfortFix0401(prefs);
        voiceOnlyMode = prefs.getBoolean(MainActivity.KEY_VOICE_ONLY, false);
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

        hapticEnabled = prefs.getBoolean(MainActivity.KEY_HAPTIC, true);
        String sizePref = prefs.getString(MainActivity.KEY_KEY_SIZE, MainActivity.DEFAULT_KEY_SIZE);
        applyKeySize(sizePref);

        String themePref = prefs.getString(MainActivity.KEY_THEME, MainActivity.DEFAULT_THEME);
        applyTheme(themePref);

        String enabledRaw = prefs.getString(MainActivity.KEY_ENABLED_LANGS, MainActivity.DEFAULT_ENABLED_LANGS);
        enabledLangs = parseEnabledLangs(enabledRaw);
        if (!enabledLangs.contains(layoutLang)) {
            layoutLang = enabledLangs.get(0);
        }
        morePage = false;
        resetBnKarState();
        shiftState = 0;
        appliedConfigSig = configSignature(
            themePref, enabledRaw, hapticEnabled, sizePref, voiceInputMode
        );
    }

    private void applyKeySize(String size) {
        if (MainActivity.SIZE_SMALL.equals(size)) {
            letterKeyHeightDp = 34;
            bottomKeyHeightDp = 42;
        } else if (MainActivity.SIZE_LARGE.equals(size)) {
            letterKeyHeightDp = 50;
            bottomKeyHeightDp = 56;
        } else {
            letterKeyHeightDp = 42;
            bottomKeyHeightDp = 50;
        }
    }

    private void applyTheme(String theme) {
        if (MainActivity.THEME_DARK.equals(theme)) {
            themeBg = 0xff1c1c1e; themeAccent = 0xff4db6ac; themeMuted = 0xffaeaeb2;
            themeKeyBg = 0xff2c2c2e; themeKeyText = 0xfff5f5f5; themeKeyStroke = 0xff3a3a3c;
        } else if (MainActivity.THEME_LIGHT.equals(theme)) {
            themeBg = 0xffeceff1; themeAccent = 0xff1a73e8; themeMuted = 0xff5f6368;
            themeKeyBg = 0xffffffff; themeKeyText = 0xff202124; themeKeyStroke = 0xffd0d4d8;
        } else if (MainActivity.THEME_COLORFUL.equals(theme)) {
            themeBg = 0xff15203a; themeAccent = 0xffffb300; themeMuted = 0xffb9c2dd;
            themeKeyBg = 0xff243056; themeKeyText = 0xffffffff; themeKeyStroke = 0xff3a4a78;
        } else { // warm (default)
            themeBg = 0xfff7f3ec; themeAccent = 0xff2f6f6d; themeMuted = 0xff5f6368;
            themeKeyBg = 0xffffffff; themeKeyText = 0xff202124; themeKeyStroke = 0xffe3ddd0;
        }
    }

    private java.util.List<String> parseEnabledLangs(String raw) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String s = part.trim();
                boolean known = MainActivity.LANG_BN.equals(s)
                    || MainActivity.LANG_EN.equals(s)
                    || MainActivity.LANG_AR.equals(s);
                if (known && !list.contains(s)) list.add(s);
            }
        }
        if (list.isEmpty()) list.add(MainActivity.LANG_BN);
        return list;
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

    /** Status strip removed — show important errors as toast only. */
    private void setStatus(String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
