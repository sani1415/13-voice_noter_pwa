package com.voicenoter.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.PopupWindow;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private static final int KEY_HEIGHT = 34;
    private static final int UTILITY_KEY_HEIGHT = 28;
    private static final int STRIP_HEIGHT = 40;
    private static final int COLOR_RECORD = 0xff2e7d32;
    private static final int COLOR_RECORD_SOFT = 0xffe8f5e9;
    private static final int COLOR_LIVE = 0xffc62828;
    private static final int COLOR_LIVE_SOFT = 0xffffebee;
    private static final int COLOR_ACTIVE_TEXT = 0xffffffff;
    private static final int BTN_CORNER_RADIUS_DP = 8;
    private static final int MAX_PRESETS = 24;

    // Sentinel keys used inside letter rows to flip between the main and "more" page.
    private static final String TOK_MORE = "\u0001MORE";
    private static final String TOK_BACK = "\u0001BACK";
    private static final String TOK_DELETE = "\u0001DELETE";

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

    // Theme colours, resolved from the selected preset in loadPreferences().
    private int themeBg = 0xfff7f3ec;
    private int themeAccent = 0xff2f6f6d;
    private int themeMuted = 0xff5f6368;
    private int themeKeyBg = 0xffffffff;
    private int themeKeyText = 0xff202124;
    private int themeKeyStroke = 0xffe3ddd0;

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
    private TextView micLabel;
    private ImageView micModeIcon;
    private TextView micHoldHint;
    private ImageButton expandArrowButton;
    private ImageButton stripGlobeButton;
    private ImageButton stripPresetButton;
    private View stripDeleteKey;
    private View stripMicKeyWrap;
    private View utilityMoreKey;
    private View karToggleKey;
    private LinearLayout bnRow1Layout;
    private LinearLayout keyboardPanel;
    private LinearLayout presetComposeBar;
    private EditText presetComposeInput;
    private LinearLayout letterContainer;
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
    private boolean voiceOnlyMode = true;
    private String layoutLang = MainActivity.LANG_BN;
    private String voiceInputMode = MainActivity.MODE_RECORD;
    private String bnKarBase = null;
    private boolean bnRow1Vowels = true;

    @Override
    public View onCreateInputView() {
        loadPreferences();
        prefetchSonioxKeyIfNeeded();
        return buildInputView();
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        if (settingsChangedSinceBuild()) {
            loadPreferences();
            setInputView(buildInputView());
        }
        prefetchSonioxKeyIfNeeded();
    }

    private boolean settingsChangedSinceBuild() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String sig = prefs.getString(MainActivity.KEY_THEME, MainActivity.DEFAULT_THEME)
            + "|" + prefs.getString(MainActivity.KEY_ENABLED_LANGS, MainActivity.DEFAULT_ENABLED_LANGS);
        return !sig.equals(appliedConfigSig);
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
        root.post(this::alignStripMicWidth);
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
        dismissGlobePopup();
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
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

        stripGlobeButton = iconButton(R.drawable.ic_globe);
        stripGlobeButton.setOnClickListener(this::showGlobeMenu);
        strip.addView(stripGlobeButton, weighted(STRIP_HEIGHT, 0.65f));

        ImageButton stripUndoButton = stripIconButton(R.drawable.ic_undo);
        stripUndoButton.setOnClickListener(v -> undoText());
        strip.addView(stripUndoButton, weighted(STRIP_HEIGHT, 0.65f));

        stripPresetButton = stripIconButton(R.drawable.ic_presets);
        stripPresetButton.setOnClickListener(this::togglePresetPopup);
        strip.addView(stripPresetButton, weighted(STRIP_HEIGHT, 0.55f));

        expandArrowButton = iconButton(R.drawable.ic_arrow_up);
        expandArrowButton.setOnClickListener(v -> toggleKeyboardPanel());
        strip.addView(expandArrowButton, weighted(STRIP_HEIGHT, 0.5f));
        updateExpandButtonIcon();

        stripDeleteKey = createDeleteKeyView(STRIP_HEIGHT);
        strip.addView(stripDeleteKey, weighted(STRIP_HEIGHT, 0.65f));

        status = new TextView(this);
        status.setText("");
        status.setTextSize(11);
        status.setTextColor(themeMuted);
        status.setSingleLine(true);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        strip.addView(status, weighted(STRIP_HEIGHT, 1f));

        stripMicKeyWrap = buildMicKeyView();
        strip.addView(stripMicKeyWrap, weighted(STRIP_HEIGHT, 0.45f));

        return strip;
    }

    private View buildMicKeyView() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setMinimumHeight(dp(STRIP_HEIGHT));
        wrap.setClickable(true);
        wrap.setOnClickListener(v -> toggleVoiceInput());
        wrap.setOnLongClickListener(v -> {
            if (isRecording || isLiveActive || isTranscribing) return false;
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
        micLabel.setTextSize(11);
        micLabel.setTypeface(Typeface.DEFAULT_BOLD);
        micLabel.setSingleLine(true);
        micLabel.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(micLabel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        micHoldHint = new TextView(this);
        micHoldHint.setText("⇄ hold");
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

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER);

        utilityRow.addView(compactActionKey("Space", () -> commitText(" ")), weighted(UTILITY_KEY_HEIGHT, 0.95f));
        utilityRow.addView(compactActionKey("Enter", () -> commitText("\n")), weighted(UTILITY_KEY_HEIGHT, 0.55f));
        utilityRow.addView(compactActionKey("All", this::selectAllText), weighted(UTILITY_KEY_HEIGHT, 0.42f));
        karToggleKey = compactIconKey(R.drawable.ic_kar_toggle, this::toggleBnVowelRow);
        utilityRow.addView(karToggleKey, weighted(UTILITY_KEY_HEIGHT, 0.45f));
        utilityMoreKey = createMoreKeyView(UTILITY_KEY_HEIGHT);
        utilityRow.addView(utilityMoreKey, weighted(UTILITY_KEY_HEIGHT, 0.45f));
        panel.addView(utilityRow, matchWidthWrap());
        updateKarToggleVisibility();

        presetComposeBar = buildPresetComposeBar();
        presetComposeBar.setVisibility(View.GONE);
        panel.addView(presetComposeBar, matchWidthWrap());

        letterContainer = new LinearLayout(this);
        letterContainer.setOrientation(LinearLayout.VERTICAL);
        panel.addView(letterContainer, matchWidthWrap());
        rebuildLetterRows();

        return panel;
    }

    private void rebuildLetterRows() {
        if (letterContainer == null) return;
        dismissSymbolPopup();
        letterContainer.removeAllViews();
        bnRow1Layout = null;
        String[][] rows = morePage ? moreRowsFor(layoutLang) : layoutRowsFor(layoutLang);
        for (int i = 0; i < rows.length; i++) {
            if (i == 0 && bnDynamicRow1Enabled()) {
                bnRow1Layout = buildDynamicKeyRow(row1Keys(), true);
                letterContainer.addView(bnRow1Layout, matchWidthWrap());
            } else {
                addTextRow(letterContainer, rows[i], false);
            }
        }
        updateKarToggleVisibility();
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
        populateKeyRow(bnRow1Layout, row1Keys(), true);
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

    private boolean isBnKarTrigger(String key) {
        if (key == null || key.length() != 1) return false;
        char c = key.charAt(0);
        if (c >= '\u0985' && c <= '\u0994') return false;
        return (c >= '\u0995' && c <= '\u09B9') || c == '\u09DC' || c == '\u09DD' || c == '\u09CE';
    }

    private String[][] moreRowsFor(String lang) {
        String[][] nums = numberRowsFor(lang);
        if (MainActivity.LANG_BN.equals(lang)) {
            return new String[][]{
                nums[0],
                nums[1],
                nums[2],
                {"\u09CC", "\u09C8", "\u09C3", "\u09C2", "\u0982", "\u0983", "\u0981", "\u09CE", "\u2018", "\u2019"},
                {"\u09E0", "\u098B", "\u098C", "\u09F3", "\u20AC", TOK_BACK},
            };
        }
        return new String[][]{
            nums[0],
            nums[1],
            nums[2],
            {TOK_BACK},
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
        if (MainActivity.LANG_EN.equals(lang)) {
            return new String[][]{
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
                {"z", "x", "c", "v", "b", "n", "m", ".", "?", TOK_DELETE},
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

    private void openMorePage() {
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
        morePage = true;
        resetBnKarState();
        rebuildLetterRows();
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
    }

    private void hidePresetCompose() {
        presetComposeActive = false;
        if (presetComposeBar != null) {
            presetComposeBar.setVisibility(View.GONE);
        }
        if (presetComposeInput != null) {
            presetComposeInput.setText("");
        }
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
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    private View compactIconKey(int drawableRes, Runnable action) {
        FrameLayout wrap = new FrameLayout(this);
        styleKeyBackground(wrap, false);
        ImageButton btn = iconButton(drawableRes);
        btn.setPadding(dp(2), dp(2), dp(2), dp(2));
        btn.setOnClickListener(v -> action.run());
        wrap.addView(btn, matchParentSquare());
        wrap.setMinimumHeight(dp(UTILITY_KEY_HEIGHT));
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
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private View createMoreKeyView(int heightDp) {
        FrameLayout wrap = new FrameLayout(this);
        styleKeyBackground(wrap, true);
        ImageButton more = iconButton(R.drawable.ic_chevron_right);
        more.setPadding(dp(4), dp(4), dp(4), dp(4));
        more.setOnClickListener(v -> openMorePage());
        more.setOnLongClickListener(v -> {
            if (MainActivity.LANG_BN.equals(layoutLang)) {
                showSymbolPopup(more, BN_DANDA_POPUP_SYMBOLS);
                return true;
            }
            return false;
        });
        wrap.addView(more, matchParentSquare());
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

    private void styleKeyBackground(View view, boolean accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(themeKeyBg);
        bg.setCornerRadius(dp(7));
        bg.setStroke(dp(1), accent ? themeAccent : themeKeyStroke);
        view.setBackground(bg);
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
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void setLayoutLanguage(String lang) {
        layoutLang = lang;
        morePage = false;
        resetBnKarState();
        rebuildLetterRows();
        savePreferences();
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
        if (stripDeleteKey != null) {
            stripDeleteKey.setVisibility(voiceOnlyMode ? View.VISIBLE : View.GONE);
        }
        if (utilityMoreKey != null) {
            utilityMoreKey.setVisibility(voiceOnlyMode ? View.GONE : View.VISIBLE);
        }
        if (voiceOnlyMode) {
            dismissSymbolPopup();
            dismissPresetPopup();
            hidePresetCompose();
        }
        updateExpandButtonIcon();
        alignStripMicWidth();
    }

    private void alignStripMicWidth() {
        if (stripMicKeyWrap == null) return;
        View reference = (utilityMoreKey != null && utilityMoreKey.getVisibility() == View.VISIBLE)
            ? utilityMoreKey
            : stripDeleteKey;
        if (reference == null) return;
        reference.post(() -> {
            int targetWidth = reference.getWidth();
            if (targetWidth <= 0) return;
            android.view.ViewGroup.LayoutParams raw = stripMicKeyWrap.getLayoutParams();
            if (!(raw instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            if (lp.width == targetWidth && lp.weight == 0f) return;
            lp.width = targetWidth;
            lp.weight = 0f;
            stripMicKeyWrap.setLayoutParams(lp);
        });
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
            applyMicKeyStyle(softBg, accent, isLiveMode ? "Live" : "Voice", iconRes, false, true);
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
                    updateLiveInsert(finalText, partialText);
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
                commitText(text);
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

    private void selectAllText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.selectAll);
    }

    private void undoText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.undo);
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

    private LinearLayout buildDynamicKeyRow(String[] keys, boolean isRow1) {
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER);
        keyRow.setPadding(0, dp(2), 0, 0);
        populateKeyRow(keyRow, keys, isRow1);
        return keyRow;
    }

    private void populateKeyRow(LinearLayout keyRow, String[] keys, boolean isRow1) {
        for (String key : keys) {
            if (TOK_DELETE.equals(key)) {
                View deleteKey = createDeleteKeyView(KEY_HEIGHT);
                keyRow.addView(deleteKey, weighted(KEY_HEIGHT, 1));
                continue;
            }
            if (TOK_BACK.equals(key)) {
                ImageButton backBtn = iconButton(R.drawable.ic_chevron_left);
                styleKeyBackground(backBtn, true);
                backBtn.setOnClickListener(v -> {
                    morePage = false;
                    resetBnKarState();
                    rebuildLetterRows();
                });
                keyRow.addView(backBtn, weighted(KEY_HEIGHT, 2f));
                continue;
            }
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(0, 0, 0, 0);
            button.setText(key);
            button.setTextSize(15);
            styleKeyButton(button, false);
            if (",".equals(key)) {
                button.setOnClickListener(v -> commitText(", "));
            } else if ("\u0964".equals(key)) {
                button.setOnClickListener(v -> commitText("\u0964"));
            } else if (isRow1) {
                button.setOnClickListener(v -> onRow1KeyPressed(key));
            } else {
                button.setOnClickListener(v -> onLetterKeyPressed(key));
            }
            keyRow.addView(button, weighted(KEY_HEIGHT, 1));
        }
    }

    private void addTextRow(LinearLayout root, String[] keys, boolean isRow1) {
        LinearLayout keyRow = buildDynamicKeyRow(keys, isRow1);
        root.addView(keyRow, matchWidthWrap());
    }

    private void styleKeyButton(Button button, boolean accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(themeKeyBg);
        bg.setCornerRadius(dp(7));
        bg.setStroke(dp(1), themeKeyStroke);
        button.setBackground(bg);
        button.setTextColor(accent ? themeAccent : themeKeyText);
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

        String themePref = prefs.getString(MainActivity.KEY_THEME, MainActivity.DEFAULT_THEME);
        applyTheme(themePref);

        String enabledRaw = prefs.getString(MainActivity.KEY_ENABLED_LANGS, MainActivity.DEFAULT_ENABLED_LANGS);
        enabledLangs = parseEnabledLangs(enabledRaw);
        if (!enabledLangs.contains(layoutLang)) {
            layoutLang = enabledLangs.get(0);
        }
        morePage = false;
        resetBnKarState();
        appliedConfigSig = themePref + "|" + enabledRaw;
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

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
