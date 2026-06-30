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
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "voice_noter_keyboard";
    static final String KEY_ENDPOINT = "endpoint";
    static final String KEY_VOICE_ONLY = "voice_only_mode";
    static final String KEY_LAYOUT_LANG = "layout_lang";
    static final String LANG_BN = "bn";
    static final String LANG_EN = "en";
    static final String LANG_AR = "ar";
    static final String KEY_VOICE_INPUT_MODE = "voice_input_mode";
    static final String MODE_RECORD = "record";
    static final String MODE_LIVE = "live";
    static final String DEFAULT_ENDPOINT = "https://notes.idarah786.com";
    private static final int REQ_AUDIO = 1001;

    private EditText endpointInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(24);
        title.setTextColor(0xff202124);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Set your deployed PWA base URL, grant microphone permission, then enable this keyboard from Android settings.");
        help.setTextSize(15);
        help.setPadding(0, dp(12), 0, dp(18));
        root.addView(help);

        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint(DEFAULT_ENDPOINT);
        endpointInput.setText(prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT));
        root.addView(endpointInput);

        Button save = new Button(this);
        save.setText("Save endpoint");
        save.setOnClickListener(v -> saveEndpoint());
        root.addView(save);

        Button permission = new Button(this);
        permission.setText("Grant microphone permission");
        permission.setOnClickListener(v -> requestMicPermission());
        root.addView(permission);

        Button enable = new Button(this);
        enable.setText("Enable keyboard");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable);

        CheckBox voiceOnly = new CheckBox(this);
        voiceOnly.setText("Start in voice-only mode (minimal bar, no letter keys)");
        voiceOnly.setChecked(prefs.getBoolean(KEY_VOICE_ONLY, true));
        voiceOnly.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
            prefs.edit().putBoolean(KEY_VOICE_ONLY, isChecked).apply()
        );
        root.addView(voiceOnly);

        Button switchKeyboard = new Button(this);
        switchKeyboard.setText("Switch keyboard");
        switchKeyboard.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(switchKeyboard);

        TextView footer = new TextView(this);
        footer.setText("Hold Voice to switch Live mode. Live uses Soniox; record uses /api/transcribe. Voice-only mode shows a slim bar with BN/EN/AR.");
        footer.setTextSize(13);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);

        setContentView(scrollView);
    }

    private void saveEndpoint() {
        String endpoint = endpointInput.getText().toString().trim();
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            Toast.makeText(this, "Use a full URL, for example https://example.vercel.app", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT, endpoint)
            .apply();
        Toast.makeText(this, "Endpoint saved", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, granted ? "Microphone permission granted" : "Microphone permission denied", Toast.LENGTH_SHORT).show();
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
