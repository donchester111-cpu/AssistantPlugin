package com.godwin.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public class AssistantAccessibilityService extends AccessibilityService {

    private static AssistantAccessibilityService instance;
    private static final String TAG = "AssistAccessibility";

    public static AssistantAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;

        setServiceInfo(info);
        Log.d(TAG, "Accessibility Service Connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Interrupted");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public String getScreenContent() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"No active window\"}";
        }

        try {
            JSONObject result = new JSONObject();
            result.put("package",
                    root.getPackageName() != null
                            ? root.getPackageName().toString()
                            : "");
            result.put("root", nodeToJson(root));
            return result.toString();
        } catch (JSONException e) {
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        } finally {
            root.recycle();
        }
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node)
            throws JSONException {

        JSONObject obj = new JSONObject();

        obj.put("class",
                node.getClassName() != null
                        ? node.getClassName().toString()
                        : "");

        obj.put("text",
                node.getText() != null
                        ? node.getText().toString()
                        : "");

        obj.put("desc",
                node.getContentDescription() != null
                        ? node.getContentDescription().toString()
                        : "");

        obj.put("id",
                node.getViewIdResourceName() != null
                        ? node.getViewIdResourceName()
                        : "");

        obj.put("clickable", node.isClickable());
        obj.put("enabled", node.isEnabled());
        obj.put("checked", node.isChecked());

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        JSONObject bounds = new JSONObject();
        bounds.put("left", rect.left);
        bounds.put("top", rect.top);
        bounds.put("right", rect.right);
        bounds.put("bottom", rect.bottom);

        obj.put("bounds", bounds);

        JSONArray children = new JSONArray();

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);

            if (child != null) {
                try {
                    children.put(nodeToJson(child));
                } finally {
                    child.recycle();
                }
            }
        }

        obj.put("children", children);
        return obj;
    }

    public boolean clickByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(text);

            for (AccessibilityNodeInfo node : nodes) {
                try {
                    if (node.isClickable()) {
                        return node.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK);
                    }
                } finally {
                    node.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "clickByText failed", e);
        } finally {
            root.recycle();
        }

        return false;
    }

    public boolean clickById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);

            for (AccessibilityNodeInfo node : nodes) {
                try {
                    if (node.isClickable()) {
                        return node.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK);
                    }
                } finally {
                    node.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "clickById failed", e);
        } finally {
            root.recycle();
        }

        return false;
    }

    public boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        try {
            AccessibilityNodeInfo focus =
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

            if (focus != null) {
                try {
                    Bundle args = new Bundle();
                    args.putCharSequence(
                            AccessibilityNodeInfo
                                    .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text);

                    return focus.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            args);
                } finally {
                    focus.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "typeText failed", e);
        } finally {
            root.recycle();
        }

        return false;
    }

    public boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
                new GestureDescription.StrokeDescription(
                        path, 0, 1));

        return dispatchGesture(builder.build(), null, null);
    }

    public boolean swipe(
            int x1,
            int y1,
            int x2,
            int y2,
            int duration) {

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
                new GestureDescription.StrokeDescription(
                        path, 0, Math.max(1, duration)));

        return dispatchGesture(builder.build(), null, null);
    }

    public boolean swipe(int x1, int y1, int x2, int y2) {
        return swipe(x1, y1, x2, y2, 200);
    }

    public boolean globalAction(int action) {
        return performGlobalAction(action);
    }

    /*
     * Important:
     * A real Android screen capture requires MediaProjection permission.
     * The plugin therefore exposes a safe error instead of pretending that
     * an arbitrary VirtualDisplay is a valid screenshot source.
     *
     * The AssistantPlugin class contains the MediaProjection-ready entry point
     * and can be connected to your Activity's capture permission flow.
     */
    public String takeScreenshot() {
        return "{\"error\":\"MediaProjection permission is required for screen capture\"}";
    }

    private static String escapeJson(String value) {
        if (value == null) return "";

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
