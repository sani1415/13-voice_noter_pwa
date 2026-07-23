package com.voicenoter.keyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingMainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    private EditText endpoint;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        setTitle("Voice Noter Floating");
        setContentView(buildScreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
        root.addView(button("Stop floating assistant", v ->
            stopService(new Intent(this, FloatingVoiceService.class))));

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
        RadioGroup langs = new RadioGroup(this);
        langs.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"বাংলা", "English", "العربية"};
        String[] values = {MainActivity.LANG_BN, MainActivity.LANG_EN, MainActivity.LANG_AR};
        String selected = prefs.getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN);
        for (int i = 0; i < labels.length; i++) {
            RadioButton item = new RadioButton(this);
            item.setText(labels[i]); item.setId(1100 + i); langs.addView(item);
            if (values[i].equals(selected)) langs.check(item.getId());
        }
        langs.setOnCheckedChangeListener((g, id) -> {
            int index = id - 1100;
            if (index >= 0 && index < values.length) {
                prefs.edit().putString(MainActivity.KEY_LAYOUT_LANG, values[index]).apply();
            }
        });
        root.addView(langs);

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
