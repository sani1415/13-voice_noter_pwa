package com.voicenoter.keyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "voice_noter_keyboard";
    static final String KEY_ENDPOINT = "endpoint";
    static final String KEY_VOICE_ONLY = "voice_only_mode";
    static final String KEY_LAYOUT_LANG = "layout_lang";
    static final String KEY_VOICE_INPUT_MODE = "voice_input_mode";
    static final String KEY_THEME = "kbd_theme";
    static final String KEY_ENABLED_LANGS = "enabled_langs";
    static final String KEY_PRESETS = "preset_messages";

    static final String DEFAULT_PRESETS = "[]";

    static final String LANG_BN = "bn";
    static final String LANG_EN = "en";
    static final String LANG_AR = "ar";

    static final String MODE_RECORD = "record";
    static final String MODE_LIVE = "live";

    static final String THEME_WARM = "warm";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";
    static final String THEME_COLORFUL = "colorful";

    static final String DEFAULT_ENDPOINT = "https://notes.idarah786.com";
    static final String DEFAULT_ENABLED_LANGS = "bn,en,ar";
    static final String DEFAULT_THEME = THEME_WARM;

    private static final int REQ_AUDIO = 1001;

    private SharedPreferences prefs;
    private EditText endpointInput;
    private TextView micStatus;

    private Button langBn, langEn, langAr;
    private Button modeRecord, modeLive;
    private Button themeWarm, themeLight, themeDark, themeColorful;
    private CheckBox chkBn, chkEn, chkAr;
    private TextView modeHelp;
    private View advancedBody;
    private TextView advancedChevron;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.AppTheme);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindSetup();
        bindLanguage();
        bindVoiceMode();
        bindVoiceOnly();
        bindEnabledLanguages();
        bindTheme();
        bindAdvanced();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMicStatus();
    }

    // ---------- Setup ----------

    private void bindSetup() {
        micStatus = findViewById(R.id.mic_status);
        findViewById(R.id.btn_mic).setOnClickListener(v -> requestMicPermission());
        findViewById(R.id.btn_enable).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        findViewById(R.id.btn_switch).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
    }

    private void refreshMicStatus() {
        if (micStatus == null) return;
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        micStatus.setText(granted ? "Permission granted" : "Tap Grant to allow recording");
        micStatus.setTextColor(granted ? getColor(R.color.success) : getColor(R.color.text_muted));
    }

    private void requestMicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        Toast.makeText(this, "Microphone permission is already granted", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            refreshMicStatus();
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            }
        }
    }

    // ---------- Default language ----------

    private void bindLanguage() {
        langBn = findViewById(R.id.lang_bn);
        langEn = findViewById(R.id.lang_en);
        langAr = findViewById(R.id.lang_ar);
        langBn.setOnClickListener(v -> selectLanguage(LANG_BN));
        langEn.setOnClickListener(v -> selectLanguage(LANG_EN));
        langAr.setOnClickListener(v -> selectLanguage(LANG_AR));
        styleLanguageChips();
    }

    private void selectLanguage(String lang) {
        prefs.edit().putString(KEY_LAYOUT_LANG, lang).apply();
        // A default language must also be shown on the keyboard.
        Set<String> enabled = enabledLangs();
        if (!enabled.contains(lang)) {
            enabled.add(lang);
            saveEnabledLangs(enabled);
            syncEnabledCheckboxes();
        }
        styleLanguageChips();
    }

    private void styleLanguageChips() {
        String current = prefs.getString(KEY_LAYOUT_LANG, LANG_BN);
        setChip(langBn, LANG_BN.equals(current));
        setChip(langEn, LANG_EN.equals(current));
        setChip(langAr, LANG_AR.equals(current));
    }

    // ---------- Voice input mode ----------

    private void bindVoiceMode() {
        modeRecord = findViewById(R.id.mode_record);
        modeLive = findViewById(R.id.mode_live);
        modeHelp = findViewById(R.id.mode_help);
        modeRecord.setOnClickListener(v -> selectMode(MODE_RECORD));
        modeLive.setOnClickListener(v -> selectMode(MODE_LIVE));
        styleModeChips();
    }

    private void selectMode(String mode) {
        prefs.edit().putString(KEY_VOICE_INPUT_MODE, mode).apply();
        styleModeChips();
    }

    private void styleModeChips() {
        String mode = prefs.getString(KEY_VOICE_INPUT_MODE, MODE_RECORD);
        boolean live = MODE_LIVE.equals(mode);
        setChip(modeRecord, !live);
        setChip(modeLive, live);
        modeHelp.setText(live
            ? "Words appear in real time as you speak. Best for longer dictation."
            : "Records first, then transcribes when you stop. Best for accuracy.");
    }

    // ---------- Voice-only toggle ----------

    private void bindVoiceOnly() {
        Switch voiceOnly = findViewById(R.id.switch_voice_only);
        voiceOnly.setChecked(prefs.getBoolean(KEY_VOICE_ONLY, true));
        voiceOnly.setOnCheckedChangeListener((b, checked) ->
            prefs.edit().putBoolean(KEY_VOICE_ONLY, checked).apply());
    }

    // ---------- Enabled languages ----------

    private void bindEnabledLanguages() {
        chkBn = findViewById(R.id.chk_bn);
        chkEn = findViewById(R.id.chk_en);
        chkAr = findViewById(R.id.chk_ar);
        syncEnabledCheckboxes();
        chkBn.setOnCheckedChangeListener((b, c) -> onEnabledChanged(LANG_BN, c));
        chkEn.setOnCheckedChangeListener((b, c) -> onEnabledChanged(LANG_EN, c));
        chkAr.setOnCheckedChangeListener((b, c) -> onEnabledChanged(LANG_AR, c));
    }

    private void onEnabledChanged(String lang, boolean checked) {
        Set<String> enabled = enabledLangs();
        if (checked) {
            enabled.add(lang);
        } else {
            if (enabled.size() <= 1) {
                Toast.makeText(this, "Keep at least one language", Toast.LENGTH_SHORT).show();
                syncEnabledCheckboxes();
                return;
            }
            enabled.remove(lang);
        }
        saveEnabledLangs(enabled);
        // If the default language was just turned off, move it to a still-enabled one.
        String current = prefs.getString(KEY_LAYOUT_LANG, LANG_BN);
        if (!enabled.contains(current)) {
            prefs.edit().putString(KEY_LAYOUT_LANG, enabled.iterator().next()).apply();
            styleLanguageChips();
        }
    }

    private void syncEnabledCheckboxes() {
        Set<String> enabled = enabledLangs();
        chkBn.setChecked(enabled.contains(LANG_BN));
        chkEn.setChecked(enabled.contains(LANG_EN));
        chkAr.setChecked(enabled.contains(LANG_AR));
    }

    private Set<String> enabledLangs() {
        String raw = prefs.getString(KEY_ENABLED_LANGS, DEFAULT_ENABLED_LANGS);
        Set<String> set = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) set.add(s);
        }
        if (set.isEmpty()) set.add(LANG_BN);
        return set;
    }

    private void saveEnabledLangs(Set<String> enabled) {
        // Persist in a stable order so the keyboard bar order is predictable.
        StringBuilder sb = new StringBuilder();
        for (String lang : Arrays.asList(LANG_BN, LANG_EN, LANG_AR)) {
            if (enabled.contains(lang)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(lang);
            }
        }
        prefs.edit().putString(KEY_ENABLED_LANGS, sb.toString()).apply();
    }

    // ---------- Theme ----------

    private void bindTheme() {
        themeWarm = findViewById(R.id.theme_warm);
        themeLight = findViewById(R.id.theme_light);
        themeDark = findViewById(R.id.theme_dark);
        themeColorful = findViewById(R.id.theme_colorful);
        themeWarm.setOnClickListener(v -> selectTheme(THEME_WARM));
        themeLight.setOnClickListener(v -> selectTheme(THEME_LIGHT));
        themeDark.setOnClickListener(v -> selectTheme(THEME_DARK));
        themeColorful.setOnClickListener(v -> selectTheme(THEME_COLORFUL));
        styleThemeChips();
    }

    private void selectTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme).apply();
        styleThemeChips();
    }

    private void styleThemeChips() {
        String theme = prefs.getString(KEY_THEME, DEFAULT_THEME);
        setChip(themeWarm, THEME_WARM.equals(theme));
        setChip(themeLight, THEME_LIGHT.equals(theme));
        setChip(themeDark, THEME_DARK.equals(theme));
        setChip(themeColorful, THEME_COLORFUL.equals(theme));
    }

    // ---------- Advanced (endpoint) ----------

    private void bindAdvanced() {
        endpointInput = findViewById(R.id.endpoint_input);
        endpointInput.setHint(DEFAULT_ENDPOINT);
        endpointInput.setText(prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT));

        advancedBody = findViewById(R.id.advanced_body);
        advancedChevron = findViewById(R.id.advanced_chevron);
        findViewById(R.id.advanced_header).setOnClickListener(v -> toggleAdvanced());

        findViewById(R.id.btn_save_endpoint).setOnClickListener(v -> saveEndpoint());
        findViewById(R.id.btn_reset_endpoint).setOnClickListener(v -> {
            endpointInput.setText(DEFAULT_ENDPOINT);
            prefs.edit().putString(KEY_ENDPOINT, DEFAULT_ENDPOINT).apply();
            Toast.makeText(this, "Reset to default endpoint", Toast.LENGTH_SHORT).show();
        });
    }

    private void toggleAdvanced() {
        boolean show = advancedBody.getVisibility() != View.VISIBLE;
        advancedBody.setVisibility(show ? View.VISIBLE : View.GONE);
        advancedChevron.setText(show ? "Hide ▴" : "Show ▾");
    }

    private void saveEndpoint() {
        String endpoint = endpointInput.getText().toString().trim();
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            Toast.makeText(this, "Use a full URL, e.g. https://example.com", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString(KEY_ENDPOINT, endpoint).apply();
        Toast.makeText(this, "Endpoint saved", Toast.LENGTH_SHORT).show();
    }

    // ---------- Chip styling ----------

    private void setChip(Button chip, boolean selected) {
        if (chip == null) return;
        chip.setBackgroundResource(selected ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(selected ? getColor(R.color.on_primary) : getColor(R.color.chip_text));
    }
}
