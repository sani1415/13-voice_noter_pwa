package com.voicenoter.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    private String liveProviderText = "";
    private int liveProviderAnchorLength = 0;
    private boolean liveUserRebased = false;
    private String liveExpectedFieldText = null;
    private int liveVisibleLength = 0;
    private String staleProviderText = null;
    private final Handler focusHandler = new Handler(Looper.getMainLooper());
    private final Runnable focusCheck = this::publishEditableFocus;
    private Boolean lastPublishedFocus;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        active = new WeakReference<>(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source != null && isUsableEditor(source) && !source.isPassword() && !isPasswordInput(source.getInputType())) {
            rememberFocus(source);
        }
        if (source != null && event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handlePossibleLiveUserEdit(source);
        }
        if (FloatingVoiceService.isRunning()) {
            focusHandler.removeCallbacks(focusCheck);
            focusHandler.postDelayed(focusCheck, 120);
        }
    }

    private void publishEditableFocus() {
        AccessibilityNodeInfo focus = findCurrentEditableFocus();
        boolean focused = focus != null && isUsableEditor(focus)
            && !focus.isPassword() && !isPasswordInput(focus.getInputType());
        if (focus != null) focus.recycle();
        if (lastPublishedFocus != null && lastPublishedFocus == focused) return;
        lastPublishedFocus = focused;
        FloatingVoiceService.onEditableFocusChanged(focused);
    }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        focusHandler.removeCallbacks(focusCheck);
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

    public static boolean isRunning() {
        return active.get() != null;
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
        if (focus == null || !isUsableEditor(focus)) {
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

        String existing = fieldText(focus);
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
        if (focus == null || !isUsableEditor(focus) || focus.isPassword()
            || isPasswordInput(focus.getInputType())) return false;

        String existing = fieldText(focus);
        int start = focus.getTextSelectionStart();
        int end = focus.getTextSelectionEnd();
        if (start < 0 || start > existing.length()) start = existing.length();
        if (end < start || end > existing.length()) end = start;

        liveTarget = AccessibilityNodeInfo.obtain(focus);
        livePrefix = existing.substring(0, start);
        liveSuffix = existing.substring(end);
        liveStart = start;
        liveSessionActive = true;
        liveProviderText = "";
        liveProviderAnchorLength = 0;
        liveUserRebased = false;
        liveExpectedFieldText = existing;
        liveVisibleLength = 0;
        staleProviderText = null;
        return true;
    }

    private boolean updateLiveTranscriptInternal(String transcript) {
        if (!liveSessionActive || liveTarget == null || !liveTarget.refresh()) return false;
        String current = fieldText(liveTarget);
        int selectionStart = liveTarget.getTextSelectionStart();
        int selectionEnd = liveTarget.getTextSelectionEnd();
        if (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd) {
            return true;
        }
        if (liveVisibleLength > 0 && selectionStart >= 0
            && (selectionStart < liveStart || selectionStart > liveStart + liveVisibleLength)) {
            rebaseLiveAtUserCursor(current, selectionStart);
            staleProviderText = transcript;
            return true;
        }
        if (liveVisibleLength > 0 && liveStart >= 0 && liveStart <= current.length()) {
            int segmentEnd = Math.min(current.length(), liveStart + liveVisibleLength);
            String actualSegment = current.substring(liveStart, segmentEnd);
            String expectedSegment = liveExpectedFieldText != null
                && liveStart + liveVisibleLength <= liveExpectedFieldText.length()
                ? liveExpectedFieldText.substring(liveStart, liveStart + liveVisibleLength) : actualSegment;
            if (!actualSegment.equals(expectedSegment)) {
                rebaseLiveAtUserCursor(current, selectionStart);
                staleProviderText = transcript;
                return true;
            }
        }
        if (staleProviderText != null) {
            if (staleProviderText.equals(transcript)) return true;
            staleProviderText = null;
        }
        liveProviderText = transcript;
        String visibleTranscript = transcript;
        if (liveUserRebased) {
            int anchor = Math.min(liveProviderAnchorLength, transcript.length());
            visibleTranscript = transcript.substring(anchor);
        }
        String combined = livePrefix + visibleTranscript + liveSuffix;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
        boolean updated = liveTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (updated) {
            liveExpectedFieldText = combined;
            liveVisibleLength = visibleTranscript.length();
            Bundle selection = new Bundle();
            int cursor = liveStart + visibleTranscript.length();
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
        liveProviderText = "";
        liveProviderAnchorLength = 0;
        liveUserRebased = false;
        liveExpectedFieldText = null;
        liveVisibleLength = 0;
        staleProviderText = null;
        return wasActive;
    }

    private void handlePossibleLiveUserEdit(AccessibilityNodeInfo source) {
        if (!liveSessionActive || source == null || !isUsableEditor(source)) return;
        String current = fieldText(source);
        if (liveExpectedFieldText != null && liveExpectedFieldText.equals(current)) return;

        int cursor = source.getTextSelectionStart();
        if (cursor < 0 || cursor > current.length()) cursor = current.length();
        rebaseLiveAtUserCursor(current, cursor);
        staleProviderText = liveProviderText;
        if (liveTarget != null) liveTarget.recycle();
        liveTarget = AccessibilityNodeInfo.obtain(source);
    }

    private void rebaseLiveAtUserCursor(String current, int cursor) {
        if (current == null) current = "";
        if (cursor < 0 || cursor > current.length()) cursor = current.length();
        livePrefix = current.substring(0, cursor);
        liveSuffix = current.substring(cursor);
        liveStart = cursor;
        liveVisibleLength = 0;
        liveProviderAnchorLength = liveProviderText.length();
        liveUserRebased = true;
        liveExpectedFieldText = current;
    }

    private static String fieldText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText()) return "";
        CharSequence value = node.getText();
        String text = value == null ? "" : value.toString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence hint = node.getHintText();
            if (hint != null && !hint.toString().isEmpty()
                && text.equalsIgnoreCase(hint.toString()) && text.length() <= 64) return "";
        }
        return text;
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
            && isUsableEditor(lastEditableFocus) && lastEditableFocus.isVisibleToUser()) {
            return lastEditableFocus;
        }
        recycleLastFocus();
        return null;
    }

    private AccessibilityNodeInfo editableFocusFrom(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null && isUsableEditor(focus)) {
            rememberFocus(focus);
            return focus;
        }
        focus = findEditorRecursively(root);
        if (focus != null) {
            rememberFocus(focus);
            return focus;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditorRecursively(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && isUsableEditor(node)
            && (node.isFocused() || node.getTextSelectionStart() >= 0)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditorRecursively(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean isUsableEditor(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        int actions = node.getActions();
        return node.isFocused() && ((actions & AccessibilityNodeInfo.ACTION_SET_TEXT) != 0
            || (actions & AccessibilityNodeInfo.ACTION_PASTE) != 0);
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
