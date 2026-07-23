package com.voicenoter.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

/**
 * Inserts dictated text into the focused editable field of whatever app is in front.
 */
public class TextInsertAccessibilityService extends AccessibilityService {
    private static TextInsertAccessibilityService instance;

    private int liveStart = -1;
    private int liveLen;
    private boolean liveActive;
    /** Soniox finals already accepted after a user edit — won't be rewritten. */
    private String liveCommittedFinal = "";
    /** Last live segment we wrote into the field. */
    private String lastWritten = "";
    /** Snapshot at user-edit time — ignore only while final+partial stay identical. */
    private String staleFinal = null;
    private String stalePartial = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private final android.os.Handler focusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable focusCheckRunnable = this::publishEditableFocus;
    private Boolean lastPublishedFocus = null;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!FloatingBubbleService.isRunning()) return;
        // Debounce focus checks — apps fire many events while typing.
        focusHandler.removeCallbacks(focusCheckRunnable);
        focusHandler.postDelayed(focusCheckRunnable, 120);
    }

    private void publishEditableFocus() {
        boolean focused = hasTextInputContext();
        if (lastPublishedFocus != null && lastPublishedFocus == focused) return;
        lastPublishedFocus = focused;
        FloatingBubbleService.onEditableFocusChanged(focused);
    }

    /**
     * True when the user is in a typing context: focused text field and/or soft keyboard visible.
     * Broader than isEditable() so Samsung Notes and similar editors are covered.
     */
    private boolean hasTextInputContext() {
        if (isImeWindowVisible()) return true;
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        boolean ok = isTextEntryNode(focused);
        if (focused != null) focused.recycle();
        if (ok) return true;

        AccessibilityNodeInfo a11y = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        ok = isTextEntryNode(a11y);
        if (a11y != null) a11y.recycle();
        return ok;
    }

    private boolean isImeWindowVisible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true;
            }
        }
        return false;
    }

    /** Used for bubble visibility — more permissive than insert targeting. */
    private static boolean isTextEntryNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        if (node.getActionList() != null) {
            if (node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
                return true;
            }
            if (node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)) {
                // Focused paste target is usually a text field.
                if (node.isFocused() || node.isAccessibilityFocused()) return true;
            }
        }
        CharSequence className = node.getClassName();
        if (className != null) {
            String cn = className.toString().toLowerCase(Locale.US);
            if (cn.contains("edittext")
                || cn.contains("textfield")
                || cn.contains("textarea")
                || cn.contains("composer")
                || cn.contains("editor")
                || cn.contains("autocompletetext")
                || cn.contains("searchview")
                || cn.contains("extractedittext")) {
                return true;
            }
            // Samsung Notes / rich editors often use custom View names.
            if ((cn.contains("note") || cn.contains("compose") || cn.contains("content"))
                && (node.isFocused() || node.isAccessibilityFocused())
                && (node.isClickable() || node.isLongClickable() || node.isFocusable())) {
                return true;
            }
        }
        // Focused node that already exposes a text selection range.
        if ((node.isFocused() || node.isAccessibilityFocused())
            && node.getTextSelectionStart() >= 0
            && node.getTextSelectionEnd() >= 0
            && node.getText() != null) {
            return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** Insert text at the caret of the focused editable field. */
    public static boolean insertAtCursor(String text) {
        TextInsertAccessibilityService svc = instance;
        if (svc == null || text == null || text.isEmpty()) return false;
        return svc.doInsertAtCursor(text);
    }

    /** Begin a live dictation session anchored at the current caret. */
    public static void beginLive() {
        TextInsertAccessibilityService svc = instance;
        if (svc == null) return;
        svc.liveActive = true;
        svc.liveLen = 0;
        svc.liveStart = -1;
        svc.liveCommittedFinal = "";
        svc.lastWritten = "";
        svc.staleFinal = null;
        svc.stalePartial = null;
        AccessibilityNodeInfo node = svc.findInsertTarget();
        if (node == null) return;
        try {
            FieldSnapshot snap = readField(node);
            int start = node.getTextSelectionStart();
            if (snap.hintOnly) {
                start = 0;
            } else if (start < 0 || start > snap.text.length()) {
                start = snap.text.length();
            }
            svc.liveStart = start;
        } finally {
            node.recycle();
        }
    }

    /**
     * Replace the live dictation segment with the latest transcript.
     * Selection: new speech replaces the selected range.
     */
    public static boolean updateLive(String finalText, String partialText) {
        TextInsertAccessibilityService svc = instance;
        if (svc == null || !svc.liveActive) return false;
        return svc.doUpdateLive(
            finalText != null ? finalText : "",
            partialText != null ? partialText : ""
        );
    }

    public static void endLive() {
        TextInsertAccessibilityService svc = instance;
        if (svc == null) return;
        svc.liveActive = false;
        svc.liveStart = -1;
        svc.liveLen = 0;
        svc.liveCommittedFinal = "";
        svc.lastWritten = "";
        svc.staleFinal = null;
        svc.stalePartial = null;
    }

    private boolean doInsertAtCursor(String toInsert) {
        AccessibilityNodeInfo node = findInsertTarget();
        if (node == null) return false;
        try {
            FieldSnapshot snap = readField(node);
            String cur = snap.text;
            int start = node.getTextSelectionStart();
            int end = node.getTextSelectionEnd();
            if (snap.hintOnly) {
                start = 0;
                end = 0;
                cur = "";
            } else {
                if (start < 0 || start > cur.length()) start = cur.length();
                if (end < 0 || end > cur.length()) end = start;
                if (end < start) {
                    int tmp = start;
                    start = end;
                    end = tmp;
                }
            }
            String next = cur.substring(0, start) + toInsert + cur.substring(end);
            if (setText(node, next)) {
                setSelection(node, start + toInsert.length(), start + toInsert.length());
                return true;
            }
            // Samsung Notes and similar: paste at caret/selection.
            if (end > start) {
                setSelection(node, start, end);
            }
            return pasteText(node, toInsert);
        } finally {
            node.recycle();
        }
    }

    private boolean doUpdateLive(String finalText, String partialText) {
        AccessibilityNodeInfo node = findInsertTarget();
        if (node == null) return false;
        try {
            FieldSnapshot snap = readField(node);
            String cur = snap.text;
            int selStart = node.getTextSelectionStart();
            int selEnd = node.getTextSelectionEnd();

            if (snap.hintOnly) {
                cur = "";
                selStart = 0;
                selEnd = 0;
                if (liveStart != 0 || liveLen != 0) {
                    liveStart = 0;
                    liveLen = 0;
                    lastWritten = "";
                }
            } else {
                if (selStart < 0) selStart = cur.length();
                if (selEnd < 0) selEnd = selStart;
                if (selStart > cur.length()) selStart = cur.length();
                if (selEnd > cur.length()) selEnd = cur.length();
            }

            boolean hasSelection = selStart != selEnd;
            if (hasSelection) {
                int a = Math.min(selStart, selEnd);
                int b = Math.max(selStart, selEnd);
                String selected = cur.substring(a, b);
                if (liveStart != a || liveLen != (b - a) || !selected.equals(lastWritten)) {
                    liveCommittedFinal = finalText;
                    staleFinal = finalText;
                    stalePartial = partialText;
                    liveStart = a;
                    liveLen = b - a;
                    lastWritten = selected;
                }
            } else {
                if (liveStart < 0 || liveStart > cur.length()) {
                    liveStart = selStart;
                    liveLen = 0;
                    lastWritten = "";
                }

                if (liveLen > 0
                    && (selStart < liveStart || selStart > liveStart + liveLen)) {
                    liveCommittedFinal = finalText;
                    staleFinal = finalText;
                    stalePartial = partialText;
                    liveStart = selStart;
                    liveLen = 0;
                    lastWritten = "";
                    return true;
                }

                if (liveLen > 0 && liveStart >= 0 && liveStart <= cur.length()) {
                    int end = Math.min(cur.length(), liveStart + liveLen);
                    String actual = cur.substring(liveStart, end);
                    if (!actual.equals(lastWritten)) {
                        liveCommittedFinal = finalText;
                        staleFinal = finalText;
                        stalePartial = partialText;
                        liveStart = selStart;
                        liveLen = 0;
                        lastWritten = "";
                        return true;
                    }
                }
            }

            if (staleFinal != null) {
                if (finalText.equals(staleFinal) && partialText.equals(stalePartial)) {
                    return true;
                }
                staleFinal = null;
                stalePartial = null;
            }

            if (!finalText.startsWith(liveCommittedFinal)) {
                liveCommittedFinal = finalText;
            }

            String suffix = finalText.substring(liveCommittedFinal.length()) + partialText;
            if (suffix.isEmpty()) {
                if (hasSelection) return true;
                if (liveLen == 0) return true;
            }

            int replaceEnd = Math.min(cur.length(), liveStart + liveLen);
            if (liveStart > replaceEnd) {
                liveStart = cur.length();
                replaceEnd = liveStart;
                liveLen = 0;
            }

            String next = cur.substring(0, liveStart) + suffix + cur.substring(replaceEnd);
            next = stripLeadingPlaceholder(next, suffix);

            if (setText(node, next)) {
                lastWritten = suffix;
                liveLen = suffix.length();
                setSelection(node, liveStart + liveLen, liveStart + liveLen);
                return true;
            }

            // Paste fallback (Samsung Notes, etc.)
            return pasteLiveSuffix(node, suffix);
        } finally {
            node.recycle();
        }
    }

    /**
     * Paste live transcript when SET_TEXT is unsupported.
     * Prefers pasting only the new delta to avoid duplicates.
     */
    private boolean pasteLiveSuffix(AccessibilityNodeInfo node, String suffix) {
        if (suffix == null) return false;
        if (suffix.equals(lastWritten)) return true;

        String toPaste;
        if (!lastWritten.isEmpty() && suffix.startsWith(lastWritten)) {
            toPaste = suffix.substring(lastWritten.length());
            if (toPaste.isEmpty()) return true;
        } else {
            // Replaced/re-anchored text — try select previous live span then paste full suffix.
            if (liveLen > 0 && liveStart >= 0) {
                setSelection(node, liveStart, liveStart + liveLen);
            }
            toPaste = suffix;
        }

        if (!pasteText(node, toPaste)) return false;
        lastWritten = suffix;
        liveLen = suffix.length();
        return true;
    }

    private boolean pasteText(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null || text.isEmpty()) return false;
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("Voice Assistant", text));
        boolean pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        if (pasted) return true;
        // Some editors need focus first.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private static String stripLeadingPlaceholder(String next, String suffix) {
        if (next == null || next.isEmpty() || suffix == null) return next;
        // "Message" + spoken text (common WhatsApp glitch)
        if (next.length() > suffix.length() && next.endsWith(suffix)) {
            String prefix = next.substring(0, next.length() - suffix.length());
            if (isLikelyPlaceholder(prefix)) return suffix;
        }
        // spoken + "Message"
        if (next.startsWith(suffix) && next.length() > suffix.length()) {
            String tail = next.substring(suffix.length());
            if (isLikelyPlaceholder(tail)) return suffix;
        }
        return next;
    }

    private static final class FieldSnapshot {
        final String text;
        final boolean hintOnly;

        FieldSnapshot(String text, boolean hintOnly) {
            this.text = text;
            this.hintOnly = hintOnly;
        }
    }

    private static FieldSnapshot readField(AccessibilityNodeInfo node) {
        if (node == null) return new FieldSnapshot("", true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText()) {
            return new FieldSnapshot("", true);
        }

        CharSequence textCs = node.getText();
        String raw = textCs != null ? textCs.toString() : "";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence hint = node.getHintText();
            if (hint != null) {
                String h = hint.toString();
                if (!h.isEmpty() && raw.equals(h)) {
                    return new FieldSnapshot("", true);
                }
                if (!h.isEmpty() && raw.equalsIgnoreCase(h)) {
                    return new FieldSnapshot("", true);
                }
            }
        }

        if (isLikelyPlaceholder(raw)) {
            return new FieldSnapshot("", true);
        }

        return new FieldSnapshot(raw, false);
    }

    /** Chat compose placeholders that apps often expose as getText() when empty. */
    private static boolean isLikelyPlaceholder(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        String lower = t.toLowerCase(Locale.US);
        return lower.equals("message")
            || lower.equals("type a message")
            || lower.equals("type a message...")
            || lower.equals("type a message…")
            || lower.equals("write a message")
            || lower.equals("send a message")
            || lower.equals("text message")
            || lower.equals("sms");
    }

    private static boolean setText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static void setSelection(AccessibilityNodeInfo node, int start, int end) {
        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
    }

    private AccessibilityNodeInfo findInsertTarget() {
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (canInsertInto(focused)) return focused;
        if (focused != null) focused.recycle();

        focused = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (canInsertInto(focused)) return focused;
        if (focused != null) focused.recycle();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    // Prefer the app window, skip the IME window itself.
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    AccessibilityNodeInfo found = findInsertTargetInTree(root);
                    root.recycle();
                    if (found != null) return found;
                }
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = findInsertTargetInTree(root);
        root.recycle();
        return found;
    }

    private AccessibilityNodeInfo findInsertTargetInTree(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (canInsertInto(focus)) return focus;
        if (focus != null) focus.recycle();

        focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (canInsertInto(focus)) return focus;
        if (focus != null) focus.recycle();

        if (canInsertInto(root) && (root.isFocused() || root.isAccessibilityFocused())) {
            return AccessibilityNodeInfo.obtain(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findInsertTargetInTree(child);
            child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private static boolean canInsertInto(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (isEditable(node) || isTextEntryNode(node)) return true;
        return node.getActionList() != null
            && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE);
    }

    /** @deprecated use findInsertTarget */
    private AccessibilityNodeInfo findEditableFocus() {
        return findInsertTarget();
    }

    private static boolean isEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        if (node.getActionList() != null
            && node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT
            )) {
            return true;
        }
        // Broader match for custom editors (insert path still needs SET_TEXT when possible).
        CharSequence className = node.getClassName();
        if (className != null) {
            String cn = className.toString().toLowerCase(Locale.US);
            if (cn.contains("edittext")
                || cn.contains("extractedittext")
                || cn.contains("autocompletetext")) {
                return true;
            }
        }
        return false;
    }
}
