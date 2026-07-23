package com.voicenoter.keyboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FloatingMainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    private EditText endpoint;
    private Button selectedLanguageButton;
    private TextView quickLanguageSummary;
    private EditText maxSessionInput;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        setTitle("Voice Noter Floating");
        setContentView(R.layout.activity_floating_main);
        bindScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void bindScreen() {
        status = findViewById(R.id.setup_status);
        endpoint = findViewById(R.id.endpoint_input);
        selectedLanguageButton = findViewById(R.id.selected_language);
        quickLanguageSummary = findViewById(R.id.quick_language_summary);
        maxSessionInput = findViewById(R.id.max_session_input);

        endpoint.setText(prefs.getString(MainActivity.KEY_ENDPOINT, MainActivity.DEFAULT_ENDPOINT));
        findViewById(R.id.btn_mic_permission).setOnClickListener(v -> requestMicrophone());
        findViewById(R.id.btn_overlay_permission).setOnClickListener(v -> openOverlayPermission());
        findViewById(R.id.btn_accessibility).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_start_assistant).setOnClickListener(v -> startAssistant());
        findViewById(R.id.btn_reset_bubble).setOnClickListener(v -> resetBubblePosition());
        findViewById(R.id.btn_close_assistant).setOnClickListener(v -> closeAssistant());
        selectedLanguageButton.setOnClickListener(v -> showLanguagePicker(false));
        findViewById(R.id.btn_manage_languages).setOnClickListener(v -> showLanguagePicker(true));
        findViewById(R.id.btn_save_endpoint).setOnClickListener(v -> saveEndpoint());
        findViewById(R.id.btn_save_session).setOnClickListener(v -> saveMaxSession());

        RadioGroup modes = findViewById(R.id.mode_group);
        String savedMode = prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
        modes.check(MainActivity.MODE_LIVE.equals(savedMode) ? R.id.mode_live : R.id.mode_record);
        modes.setOnCheckedChangeListener((group, checkedId) -> prefs.edit().putString(
            MainActivity.KEY_VOICE_INPUT_MODE,
            checkedId == R.id.mode_live ? MainActivity.MODE_LIVE : MainActivity.MODE_RECORD).apply());

        RadioGroup visibility = findViewById(R.id.visibility_group);
        String savedVisibility = prefs.getString(MainActivity.KEY_BUBBLE_VISIBILITY, MainActivity.BUBBLE_ALWAYS);
        visibility.check(MainActivity.BUBBLE_TEXT_FIELD.equals(savedVisibility)
            ? R.id.visibility_text_field : R.id.visibility_always);
        visibility.setOnCheckedChangeListener((group, checkedId) -> {
            boolean textOnly = checkedId == R.id.visibility_text_field;
            prefs.edit().putString(MainActivity.KEY_BUBBLE_VISIBILITY,
                textOnly ? MainActivity.BUBBLE_TEXT_FIELD : MainActivity.BUBBLE_ALWAYS).apply();
            if (textOnly && !VoiceAccessibilityService.isRunning()) {
                Toast.makeText(this, "Enable Text insert for text-area visibility", Toast.LENGTH_LONG).show();
            }
        });
        loadMaxSession();
        refreshLanguageLabels();
    }

    private void loadMaxSession() {
        int seconds = prefs.getInt(MainActivity.KEY_MAX_SESSION_SEC, MainActivity.DEFAULT_MAX_SESSION_SEC);
        maxSessionInput.setText(seconds <= 0 ? "" : String.valueOf(Math.max(1, seconds / 60)));
    }

    private void saveMaxSession() {
        String raw = maxSessionInput.getText().toString().trim();
        int seconds = 0;
        if (!raw.isEmpty()) {
            try {
                int minutes = Math.max(1, Math.min(1440, Integer.parseInt(raw)));
                seconds = minutes * 60;
                maxSessionInput.setText(String.valueOf(minutes));
            } catch (NumberFormatException error) {
                Toast.makeText(this, "Enter valid minutes", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        prefs.edit().putInt(MainActivity.KEY_MAX_SESSION_SEC, seconds).apply();
        Toast.makeText(this, seconds <= 0 ? "Session limit: unlimited" : "Session limit saved", Toast.LENGTH_SHORT).show();
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(247, 243, 236));

        TextView title = text("Voice Noter Floating", 25, true);
        root.addView(title);
        TextView intro = text("Use Gboard or any keyboard. The movable mic bubble types your transcript into the active field.", 15, false);
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        status = text("Checking permissions…", 14, true);
        status.setTextColor(Color.rgb(47, 111, 109));
        root.addView(status);

        root.addView(button("1. Allow microphone", v -> requestMicrophone()));
        root.addView(button("2. Allow floating bubble", v -> openOverlayPermission()));
        root.addView(button("3. Enable text insertion", v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("Start floating assistant", v -> startAssistant()));
        root.addView(button("Bring bubble back to screen", v -> resetBubblePosition()));
        root.addView(button("Close floating assistant", v -> closeAssistant()));

        TextView modeTitle = text("Voice mode", 16, true);
        modeTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(modeTitle);
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton record = new RadioButton(this);
        record.setText("Record"); record.setId(1001);
        RadioButton live = new RadioButton(this);
        live.setText("Live"); live.setId(1002);
        modes.addView(record); modes.addView(live);
        String savedMode = prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
        modes.check(MainActivity.MODE_LIVE.equals(savedMode) ? 1002 : 1001);
        modes.setOnCheckedChangeListener((g, id) -> prefs.edit().putString(
            MainActivity.KEY_VOICE_INPUT_MODE,
            id == 1002 ? MainActivity.MODE_LIVE : MainActivity.MODE_RECORD).apply());
        root.addView(modes);

        TextView languageTitle = text("Language", 16, true);
        languageTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(languageTitle);
        selectedLanguageButton = button("", v -> showLanguagePicker(false));
        root.addView(selectedLanguageButton);
        quickLanguageSummary = text("", 13, false);
        quickLanguageSummary.setPadding(0, dp(6), 0, 0);
        root.addView(quickLanguageSummary);
        root.addView(button("Manage quick languages", v -> showLanguagePicker(true)));
        refreshLanguageLabels();

        TextView endpointTitle = text("Backend endpoint", 16, true);
        endpointTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(endpointTitle);
        endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setText(prefs.getString(MainActivity.KEY_ENDPOINT, MainActivity.DEFAULT_ENDPOINT));
        root.addView(endpoint);
        root.addView(button("Save endpoint", v -> saveEndpoint()));

        return root;
    }

    private void showLanguagePicker(boolean manageQuick) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search language");
        panel.addView(search, new LinearLayout.LayoutParams(-1, dp(50)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(430)));

        Set<String> checked = new LinkedHashSet<>(LanguageRegistry.parseQuick(
            prefs.getString(MainActivity.KEY_QUICK_LANGS, LanguageRegistry.DEFAULT_QUICK)));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(manageQuick ? "Quick languages" : "Transcription language")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(manageQuick ? "Save" : "Close", null)
            .create();

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.US);
            list.removeAllViews();
            for (LanguageRegistry.Language language : LanguageRegistry.all()) {
                String searchable = (language.code + " " + language.englishName + " " + language.nativeName)
                    .toLowerCase(Locale.US);
                if (!query.isEmpty() && !searchable.contains(query)) continue;
                if (manageQuick) {
                    CheckBox item = new CheckBox(this);
                    item.setText(language.displayName() + "  (" + language.shortLabel() + ")");
                    item.setChecked(checked.contains(language.code));
                    item.setPadding(dp(4), dp(7), dp(4), dp(7));
                    item.setOnCheckedChangeListener((button, isChecked) -> {
                        if (isChecked) checked.add(language.code); else checked.remove(language.code);
                    });
                    list.addView(item);
                } else {
                    TextView item = text(language.displayName() + "  (" + language.shortLabel() + ")", 15, false);
                    item.setPadding(dp(8), dp(13), dp(8), dp(13));
                    item.setOnClickListener(v -> {
                        prefs.edit().putString(MainActivity.KEY_LAYOUT_LANG, language.code).apply();
                        ensureCurrentIsQuick(language.code);
                        refreshLanguageLabels();
                        dialog.dismiss();
                    });
                    list.addView(item);
                }
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild[0].run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        dialog.setOnShowListener(ignored -> {
            if (manageQuick) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (checked.isEmpty()) {
                        Toast.makeText(this, "Choose at least one quick language", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(MainActivity.KEY_QUICK_LANGS, String.join(",", checked)).apply();
                    String current = prefs.getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN);
                    if (!checked.contains(current)) {
                        prefs.edit().putString(MainActivity.KEY_LAYOUT_LANG, checked.iterator().next()).apply();
                    }
                    refreshLanguageLabels();
                    dialog.dismiss();
                });
            }
        });
        rebuild[0].run();
        dialog.show();
    }

    private void ensureCurrentIsQuick(String code) {
        List<String> quick = LanguageRegistry.parseQuick(
            prefs.getString(MainActivity.KEY_QUICK_LANGS, LanguageRegistry.DEFAULT_QUICK));
        if (quick.contains(code)) return;
        quick.add(code);
        prefs.edit().putString(MainActivity.KEY_QUICK_LANGS, String.join(",", quick)).apply();
    }

    private void refreshLanguageLabels() {
        String current = prefs.getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN);
        LanguageRegistry.Language selected = LanguageRegistry.find(current);
        if (selectedLanguageButton != null) {
            selectedLanguageButton.setText(selected.displayName() + "  (" + selected.shortLabel() + ")");
        }
        if (quickLanguageSummary != null) {
            StringBuilder summary = new StringBuilder("Quick cycle: ");
            List<String> quick = LanguageRegistry.parseQuick(
                prefs.getString(MainActivity.KEY_QUICK_LANGS, LanguageRegistry.DEFAULT_QUICK));
            for (int i = 0; i < quick.size(); i++) {
                if (i > 0) summary.append("  ·  ");
                summary.append(LanguageRegistry.find(quick.get(i)).shortLabel());
            }
            quickLanguageSummary.setText(summary.toString());
        }
    }

    private void requestMicrophone() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 20);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
        }
    }

    private void openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void startAssistant() {
        saveEndpoint();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            openOverlayPermission();
            Toast.makeText(this, "Allow the floating window first", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, FloatingVoiceService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        Toast.makeText(this, "Floating assistant started", Toast.LENGTH_SHORT).show();
    }

    private void resetBubblePosition() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            openOverlayPermission();
            return;
        }
        Intent intent = new Intent(this, FloatingVoiceService.class);
        intent.setAction(FloatingVoiceService.ACTION_RESET_POSITION);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void closeAssistant() {
        stopService(new Intent(this, FloatingVoiceService.class));
        Toast.makeText(this, "Floating assistant fully closed", Toast.LENGTH_SHORT).show();
    }

    private void saveEndpoint() {
        String value = endpoint.getText().toString().trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            Toast.makeText(this, "Endpoint must start with https:// or http://", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(MainActivity.KEY_ENDPOINT, value).apply();
    }

    private void refreshStatus() {
        boolean mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean access = VoiceAccessibilityService.isEnabled(this);
        status.setText("Microphone: " + yes(mic) + "   Overlay: " + yes(overlay) + "   Text access: " + yes(access));
    }

    private String yes(boolean value) { return value ? "Ready" : "Needed"; }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(Color.rgb(32, 33, 36));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value); button.setAllCaps(false); button.setGravity(Gravity.CENTER_VERTICAL);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(8); button.setLayoutParams(lp);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
