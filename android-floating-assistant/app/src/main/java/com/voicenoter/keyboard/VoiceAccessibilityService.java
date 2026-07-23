package com.voicenoter.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public class VoiceAccessibilityService extends AccessibilityService {
    private static WeakReference<VoiceAccessibilityService> active = new WeakReference<>(null);
    private AccessibilityNodeInfo lastEditableFocus;
    private AccessibilityNodeInfo liveTarget;
    private String livePrefix = "";
    private String liveSuffix = "";
    private int liveStart = 0;
    private boolean liveSessionActive = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        active = new WeakReference<>(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        if (source.isEditable() && !source.isPassword() && !isPasswordInput(source.getInputType())) {
            rememberFocus(source);
        }
    }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        finishLiveInsertion();
        recycleLastFocus();
        if (active.get() == this) active.clear();
        super.onDestroy();
    }

    public static boolean insertTranscript(Context context, String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return false;
        VoiceAccessibilityService service = active.get();
        if (service == null) {
            copy(context, transcript);
            Toast.makeText(context, "Transcript copied — enable text insertion or paste it", Toast.LENGTH_LONG).show();
            return false;
        }
        return service.insertIntoFocusedField(transcript);
    }

    public static boolean beginLiveInsertion(Context context) {
        VoiceAccessibilityService service = active.get();
        if (service == null) return false;
        return service.beginLiveInsertionInternal();
    }

    public static boolean updateLiveTranscript(String transcript) {
        VoiceAccessibilityService service = active.get();
        return service != null && service.updateLiveTranscriptInternal(transcript == null ? "" : transcript);
    }

    public static boolean finishLiveInsertion() {
        VoiceAccessibilityService service = active.get();
        if (service == null) return false;
        return service.finishLiveInsertionInternal();
    }

    private boolean insertIntoFocusedField(String transcript) {
        AccessibilityNodeInfo focus = findCurrentEditableFocus();
        if (focus == null || !focus.isEditable()) {
            copy(this, transcript);
            Toast.makeText(this, "No editable field found — transcript copied", Toast.LENGTH_LONG).show();
            return false;
        }
        if (focus.isPassword() || isPasswordInput(focus.getInputType())) {
            copy(this, transcript);
            Toast.makeText(this, "Password fields are protected — transcript copied", Toast.LENGTH_LONG).show();
            return false;
        }

        copy(this, transcript);
        if (focus.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            && focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true;

        CharSequence oldText = focus.getText();
        String existing = oldText == null ? "" : oldText.toString();
        int start = focus.getTextSelectionStart();
        int end = focus.getTextSelectionEnd();
        if (start < 0 || start > existing.length()) start = existing.length();
        if (end < start || end > existing.length()) end = start;
        String combined = existing.substring(0, start) + transcript + existing.substring(end);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
        boolean inserted = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (inserted) {
            Bundle selection = new Bundle();
            int cursor = start + transcript.length();
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
        }
        if (!inserted) Toast.makeText(this, "Transcript copied — tap Paste", Toast.LENGTH_LONG).show();
        return inserted;
    }

    private boolean beginLiveInsertionInternal() {
        finishLiveInsertionInternal();
        AccessibilityNodeInfo focus = findCurrentEditableFocus();
        if (focus == null || !focus.isEditable() || focus.isPassword()
            || isPasswordInput(focus.getInputType())) return false;

        CharSequence currentText = focus.getText();
        String existing = currentText == null ? "" : currentText.toString();
        int start = focus.getTextSelectionStart();
        int end = focus.getTextSelectionEnd();
        if (start < 0 || start > existing.length()) start = existing.length();
        if (end < start || end > existing.length()) end = start;

        liveTarget = AccessibilityNodeInfo.obtain(focus);
        livePrefix = existing.substring(0, start);
        liveSuffix = existing.substring(end);
        liveStart = start;
        liveSessionActive = true;
        return true;
    }

    private boolean updateLiveTranscriptInternal(String transcript) {
        if (!liveSessionActive || liveTarget == null || !liveTarget.refresh()) return false;
        String combined = livePrefix + transcript + liveSuffix;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
        boolean updated = liveTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (updated) {
            Bundle selection = new Bundle();
            int cursor = liveStart + transcript.length();
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
            liveTarget.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
        }
        return updated;
    }

    private boolean finishLiveInsertionInternal() {
        boolean wasActive = liveSessionActive;
        liveSessionActive = false;
        if (liveTarget != null) {
            liveTarget.recycle();
            liveTarget = null;
        }
        livePrefix = "";
        liveSuffix = "";
        liveStart = 0;
        return wasActive;
    }

    private AccessibilityNodeInfo findCurrentEditableFocus() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo focus = editableFocusFrom(root);
        if (focus != null) return focus;

        if (Build.VERSION.SDK_INT >= 21) {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo windowRoot = window.getRoot();
                focus = editableFocusFrom(windowRoot);
                if (focus != null) return focus;
            }
        }

        if (lastEditableFocus != null && lastEditableFocus.refresh()
            && lastEditableFocus.isEditable() && lastEditableFocus.isVisibleToUser()) {
            return lastEditableFocus;
        }
        recycleLastFocus();
        return null;
    }

    private AccessibilityNodeInfo editableFocusFrom(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null && focus.isEditable()) {
            rememberFocus(focus);
            return focus;
        }
        return null;
    }

    private void rememberFocus(AccessibilityNodeInfo node) {
        if (node == null) return;
        recycleLastFocus();
        lastEditableFocus = AccessibilityNodeInfo.obtain(node);
    }

    private void recycleLastFocus() {
        if (lastEditableFocus != null) {
            lastEditableFocus.recycle();
            lastEditableFocus = null;
        }
    }

    private static boolean isPasswordInput(int inputType) {
        int variation = inputType & (InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION);
        return variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
            || variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            || variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            || variation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Voice transcript", text));
    }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = new ComponentName(context, VoiceAccessibilityService.class).flattenToString();
        return enabled.toLowerCase().contains(component.toLowerCase());
    }
}
