package com.voicenoter.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Inserts dictated text into the focused editable field of whatever app is in front.
 */
public class TextInsertAccessibilityService extends AccessibilityService {
    private static TextInsertAccessibilityService instance;

    private int liveStart = -1;
    private int liveLen;
    private boolean liveActive;

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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Content is read on demand when inserting.
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
        AccessibilityNodeInfo node = svc.findEditableFocus();
        if (node == null) return;
        try {
            CharSequence current = safeText(node);
            String cur = current != null ? current.toString() : "";
            int start = node.getTextSelectionStart();
            if (start < 0 || start > cur.length()) start = cur.length();
            svc.liveStart = start;
        } finally {
            node.recycle();
        }
    }

    /** Replace the live dictation segment with the latest transcript. */
    public static boolean updateLive(String displayText) {
        TextInsertAccessibilityService svc = instance;
        if (svc == null || !svc.liveActive) return false;
        return svc.doUpdateLive(displayText != null ? displayText : "");
    }

    public static void endLive() {
        TextInsertAccessibilityService svc = instance;
        if (svc == null) return;
        svc.liveActive = false;
        svc.liveStart = -1;
        svc.liveLen = 0;
    }

    private boolean doInsertAtCursor(String toInsert) {
        AccessibilityNodeInfo node = findEditableFocus();
        if (node == null) return false;
        try {
            CharSequence current = safeText(node);
            String cur = current != null ? current.toString() : "";
            int start = node.getTextSelectionStart();
            int end = node.getTextSelectionEnd();
            if (start < 0 || start > cur.length()) start = cur.length();
            if (end < 0 || end > cur.length()) end = start;
            if (end < start) {
                int tmp = start;
                start = end;
                end = tmp;
            }
            String next = cur.substring(0, start) + toInsert + cur.substring(end);
            if (!setText(node, next)) return false;
            int caret = start + toInsert.length();
            setSelection(node, caret, caret);
            return true;
        } finally {
            node.recycle();
        }
    }

    private boolean doUpdateLive(String displayText) {
        AccessibilityNodeInfo node = findEditableFocus();
        if (node == null) return false;
        try {
            CharSequence current = safeText(node);
            String cur = current != null ? current.toString() : "";
            if (liveStart < 0 || liveStart > cur.length()) {
                int start = node.getTextSelectionStart();
                if (start < 0 || start > cur.length()) start = cur.length();
                liveStart = start;
                liveLen = 0;
            }
            int replaceEnd = Math.min(cur.length(), liveStart + liveLen);
            if (liveStart > replaceEnd) {
                liveStart = cur.length();
                replaceEnd = liveStart;
                liveLen = 0;
            }
            String next = cur.substring(0, liveStart) + displayText + cur.substring(replaceEnd);
            if (!setText(node, next)) return false;
            liveLen = displayText.length();
            int caret = liveStart + liveLen;
            setSelection(node, caret, caret);
            return true;
        } finally {
            node.recycle();
        }
    }

    private static CharSequence safeText(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText()) {
            return "";
        }
        return node.getText();
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

    private AccessibilityNodeInfo findEditableFocus() {
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (isEditable(focused)) return focused;
        if (focused != null) focused.recycle();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    AccessibilityNodeInfo found = findEditableInTree(root);
                    root.recycle();
                    if (found != null) return found;
                }
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = findEditableInTree(root);
        root.recycle();
        return found;
    }

    private AccessibilityNodeInfo findEditableInTree(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (isEditable(focus)) return focus;
        if (focus != null) focus.recycle();

        if (isEditable(root)) {
            return AccessibilityNodeInfo.obtain(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findEditableInTree(child);
            child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        return node.getActionList() != null
            && node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT
            );
    }
}
