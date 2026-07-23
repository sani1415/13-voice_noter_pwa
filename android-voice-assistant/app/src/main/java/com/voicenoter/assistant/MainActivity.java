package com.voicenoter.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "voice_assistant";
    static final String KEY_ENDPOINT = "endpoint";
    static final String KEY_LAYOUT_LANG = "layout_lang";
    static final String KEY_VOICE_INPUT_MODE = "voice_input_mode";

    static final String LANG_BN = "bn";
    static final String LANG_EN = "en";
    static final String LANG_AR = "ar";

    static final String MODE_RECORD = "record";
    static final String MODE_LIVE = "live";

    static final String DEFAULT_ENDPOINT = "https://notes.idarah786.com";

    private static final int REQ_AUDIO = 1001;
    private static final int REQ_NOTIF = 1002;

    private SharedPreferences prefs;
    private EditText endpointInput;
    private TextView overlayStatus;
    private TextView a11yStatus;
    private TextView micStatus;
    private TextView modeHelp;
    private Button langBn, langEn, langAr;
    private Button modeRecord, modeLive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.AppTheme);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        overlayStatus = findViewById(R.id.overlay_status);
        a11yStatus = findViewById(R.id.a11y_status);
        micStatus = findViewById(R.id.mic_status);
        modeHelp = findViewById(R.id.mode_help);
        endpointInput = findViewById(R.id.endpoint_input);

        langBn = findViewById(R.id.lang_bn);
        langEn = findViewById(R.id.lang_en);
        langAr = findViewById(R.id.lang_ar);
        modeRecord = findViewById(R.id.mode_record);
        modeLive = findViewById(R.id.mode_live);

        findViewById(R.id.btn_overlay).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btn_a11y).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btn_mic).setOnClickListener(v -> requestMic());
        findViewById(R.id.btn_start_bubble).setOnClickListener(v -> startBubble());
        findViewById(R.id.btn_stop_bubble).setOnClickListener(v -> {
            FloatingBubbleService.stop(this);
            Toast.makeText(this, "Floating mic hidden", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_save_endpoint).setOnClickListener(v -> saveEndpoint());

        langBn.setOnClickListener(v -> selectLanguage(LANG_BN));
        langEn.setOnClickListener(v -> selectLanguage(LANG_EN));
        langAr.setOnClickListener(v -> selectLanguage(LANG_AR));
        modeRecord.setOnClickListener(v -> selectMode(MODE_RECORD));
        modeLive.setOnClickListener(v -> selectMode(MODE_LIVE));

        endpointInput.setHint(DEFAULT_ENDPOINT);
        endpointInput.setText(prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT));

        refreshLanguageChips();
        refreshModeChips();
        maybeRequestNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
    }

    private void refreshPermissionStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay
            ? "Overlay: allowed — floating mic can appear over other apps"
            : "Overlay: required — Android will open Settings");

        boolean a11y = TextInsertAccessibilityService.isRunning();
        a11yStatus.setText(a11y
            ? "Auto-insert: on — text goes into the focused field"
            : "Auto-insert: off — enable Accessibility for Voice Assistant");

        boolean mic = hasMic();
        micStatus.setText(mic
            ? "Microphone: granted"
            : "Microphone: required for voice typing");
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Accessibility → Voice Assistant", Toast.LENGTH_LONG).show();
        }
    }

    private void requestMic() {
        if (hasMic()) {
            Toast.makeText(this, "Microphone already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow draw over other apps first", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        if (!hasMic()) {
            Toast.makeText(this, "Grant microphone first", Toast.LENGTH_LONG).show();
            requestMic();
            return;
        }
        if (!TextInsertAccessibilityService.isRunning()) {
            Toast.makeText(this,
                "Tip: enable Accessibility for auto-insert (clipboard still works)",
                Toast.LENGTH_LONG).show();
        }
        FloatingBubbleService.start(this);
        Toast.makeText(this, "Floating mic shown — drag to move", Toast.LENGTH_SHORT).show();
    }

    private void saveEndpoint() {
        String value = endpointInput.getText() != null
            ? endpointInput.getText().toString().trim()
            : "";
        if (value.isEmpty()) value = DEFAULT_ENDPOINT;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        prefs.edit().putString(KEY_ENDPOINT, value).apply();
        endpointInput.setText(value);
        Toast.makeText(this, "Endpoint saved", Toast.LENGTH_SHORT).show();
    }

    private void selectLanguage(String lang) {
        prefs.edit().putString(KEY_LAYOUT_LANG, lang).apply();
        refreshLanguageChips();
    }

    private void selectMode(String mode) {
        prefs.edit().putString(KEY_VOICE_INPUT_MODE, mode).apply();
        refreshModeChips();
    }

    private void refreshLanguageChips() {
        String current = prefs.getString(KEY_LAYOUT_LANG, LANG_BN);
        setChip(langBn, LANG_BN.equals(current));
        setChip(langEn, LANG_EN.equals(current));
        setChip(langAr, LANG_AR.equals(current));
    }

    private void refreshModeChips() {
        String mode = prefs.getString(KEY_VOICE_INPUT_MODE, MODE_RECORD);
        setChip(modeRecord, MODE_RECORD.equals(mode));
        setChip(modeLive, MODE_LIVE.equals(mode));
        if (modeHelp != null) {
            modeHelp.setText(MODE_LIVE.equals(mode)
                ? "Live: real-time via Soniox. Text inserts as you speak (Accessibility)."
                : "Record: tap mic to start/stop, then text inserts into the focused field.");
        }
    }

    private void setChip(Button button, boolean selected) {
        if (button == null) return;
        Drawable bg = getResources().getDrawable(
            selected ? R.drawable.chip_selected : R.drawable.chip_unselected
        );
        button.setBackground(bg);
        button.setTextColor(getResources().getColor(
            selected ? R.color.on_primary : R.color.chip_text
        ));
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissionStatus();
        if (requestCode == REQ_AUDIO
            && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone granted", Toast.LENGTH_SHORT).show();
        }
    }
}
