package com.wirelessdebug.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.wirelessdebug.WdbContext;

import java.util.Locale;

/**
 * 免 Root 无障碍控制通道（与无线调试二选一）：
 * 读屏为自研节点树遍历（提取文本/描述/标志/坐标，全程无截图、无 OCR），
 * 点击/滑动/长按/双击走手势注入（Android 7.0+，低版本回退节点 ACTION_CLICK），
 * 输入走 ACTION_SET_TEXT，按键走全局动作。
 */
public class AccessibilityDevice {
    private static final String TAG = "AccessibilityDevice";
    private static final int DUMP_LIMIT = 220;
    private static final int DUMP_MAX_CHARS = 6000;

    /** 无障碍服务是否已在系统设置中开启（对比 Settings.Secure 的已启用服务列表）。 */
    public static boolean isEnabled() {
        try {
            Context c = WdbContext.get();
            if (c == null) return false;
            String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String flat = c.getPackageName() + "/" + AccessibilityControlService.class.getName();
            for (String s : enabled.split(":")) {
                if (s != null && s.equalsIgnoreCase(flat)) return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "isEnabled: " + t.getMessage());
        }
        return false;
    }

    /** 无障碍服务是否已连接（系统已绑定）。 */
    public static boolean isConnected() {
        return AccessibilityControlService.instance() != null;
    }

    /** 无障碍是否作为当前激活通道（无 Root/无线调试/Shizuku 但无障碍已开启并连接）。 */
    public static boolean isActiveChannel() {
        return isEnabled() && isConnected()
            && !RootController.canUseRoot() && !AdbShellController.isConnected() && !ShizukuController.hasPermission();
    }

    public static String tap(int x, int y) {
        if (Build.VERSION.SDK_INT >= 24) {
            return gesture(70, x, y, x, y) ? "tapped (" + x + "," + y + ")" : "tap failed: accessibility gesture unavailable";
        }
        AccessibilityNodeInfo node = nodeAt(x, y);
        if (node == null) return "tap failed: no clickable node at (" + x + "," + y + ")";
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.recycle();
        return ok ? "tapped (" + x + "," + y + ")" : "tap failed: node rejected ACTION_CLICK";
    }

    public static String swipe(int x1, int y1, int x2, int y2, long durationMs) {
        if (Build.VERSION.SDK_INT < 24) {
            return "swipe failed: accessibility gestures require Android 7.0+";
        }
        return gesture(durationMs, x1, y1, x2, y2) ? "swiped" : "swipe failed: accessibility gesture unavailable";
    }

    /** 长按（坐标）：同点按住 durationMs（手势注入或节点 ACTION_LONG_CLICK）。 */
    public static String longPress(int x, int y, long durationMs) {
        if (Build.VERSION.SDK_INT >= 24) {
            return gesture(durationMs, x, y, x, y) ? "long-pressed (" + x + "," + y + ")" : "long press failed: accessibility gesture unavailable";
        }
        AccessibilityNodeInfo node = nodeAt(x, y);
        if (node == null) return "long press failed: no node at (" + x + "," + y + ")";
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        node.recycle();
        return ok ? "long-pressed (" + x + "," + y + ")" : "long press failed: node rejected ACTION_LONG_CLICK";
    }

    /** 双击（坐标）：两次点击，间隔 90ms。 */
    public static String doubleTap(int x, int y) {
        if (!tap(x, y).startsWith("tapped")) return "double tap failed";
        sleepQuiet(90);
        return tap(x, y).startsWith("tapped") ? "double-tapped" : "second tap failed";
    }

    public static String back() { return globalAction(AccessibilityService.GLOBAL_ACTION_BACK) ? "back" : "back failed"; }
    public static String home() { return globalAction(AccessibilityService.GLOBAL_ACTION_HOME) ? "home" : "home failed"; }
    public static String recents() { return globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ? "recents" : "recents failed"; }

    public static String notifications() {
        return globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ? "notifications expanded" : "notifications failed";
    }

    /**
     * 按键映射：back/home/recents 走全局动作；enter/delete/paste 走焦点节点动作；
     * power/音量等需要系统级注入的按键在无障碍通道不支持（无 Root）。
     */
    public static String keyEvent(String code) {
        switch (code) {
            case "4": return back();
            case "3": return home();
            case "187": return recents();
            case "66": { // enter：优先点击当前焦点节点（搜索/发送按钮）
                AccessibilityNodeInfo focus = focusedNode();
                if (focus == null) return "keyevent 66 unsupported: no focused input; use tap_item/tap_text to click the search/send button";
                boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                focus.recycle();
                return ok ? "keyevent 66 sent (click focused node)" : "keyevent 66 unsupported: focused node not clickable";
            }
            case "67": { // delete：删除焦点输入框最后一个字符
                AccessibilityNodeInfo focus = focusedNode();
                if (focus == null) return "keyevent 67 unsupported: no focused input";
                CharSequence cur = focus.getText();
                String s = cur == null ? "" : cur.toString();
                if (s.isEmpty()) {
                    focus.recycle();
                    return "keyevent 67: input already empty";
                }
                boolean ok = setText(focus, s.substring(0, s.length() - 1));
                focus.recycle();
                return ok ? "keyevent 67 sent" : "keyevent 67 failed";
            }
            case "279": { // paste：焦点输入框粘贴剪贴板
                AccessibilityNodeInfo focus = focusedNode();
                if (focus == null) return "keyevent 279 unsupported: no focused input";
                boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                focus.recycle();
                return ok ? "keyevent 279 sent (paste)" : "keyevent 279 failed";
            }
            default:
                return "keyevent " + code + " unsupported on accessibility channel (no root)";
        }
    }

    /** 输入文本：定位焦点输入框并 ACTION_SET_TEXT（支持中文等任意字符，无需剪贴板）。 */
    public static String inputText(String text) {
        if (text == null || text.isEmpty()) return "input text required";
        AccessibilityNodeInfo focus = focusedNode();
        if (focus != null) {
            boolean ok = setText(focus, text);
            focus.recycle();
            if (ok) return "input sent";
            return "input failed: ACTION_SET_TEXT rejected";
        }
        // 无焦点输入框：给第一个可编辑节点设焦点后重试
        AccessibilityNodeInfo editable = findEditableNode();
        if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            sleepQuiet(120);
            boolean ok = setText(editable, text);
            editable.recycle();
            if (ok) return "input sent";
        }
        return "input failed: no focused editable node on screen";
    }

    /** 打开应用：通过应用管理器解析入口 Activity 并前台启动。 */
    public static String openApp(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "package required";
        AccessibilityControlService svc = AccessibilityControlService.instance();
        if (svc == null) return "open failed: accessibility service not connected";
        try {
            Context c = svc.getApplicationContext();
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg.trim());
            if (launch == null) return "open failed: no launcher activity for " + pkg;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(launch);
            return "opened: " + pkg;
        } catch (Throwable t) {
            return "open failed: " + (t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /** 读取屏幕物理尺寸，格式 WxH；失败返回 unknown。 */
    public static String screenSize() {
        AccessibilityControlService svc = AccessibilityControlService.instance();
        if (svc == null) return "unknown";
        try {
            DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            return dm.widthPixels + "x" + dm.heightPixels;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * 自研读屏：遍历无障碍节点树，提取文本/描述/可点/可编辑/可滚动与屏幕坐标，
     * 输出与无线调试通道一致的摘要格式（app= / screen= / "- \"文本\" 标志 bounds=(l,t,r,b)"）。
     * 全程不截图、不 OCR，任意应用只要能走无障碍即可识别。
     */
    public static String screenDump() {
        AccessibilityControlService svc = AccessibilityControlService.instance();
        if (svc == null) return "screen dump failed: accessibility service not connected";
        // 打开应用/窗口切换过渡期 root 常为 null：统一入口已带重试
        AccessibilityNodeInfo root = rootWithRetry();
        if (root == null) return "screen dump failed: no active window (accessibility, retried)";
        try {
            // 窗口切换后节点可能已过期：刷新一次，失败则整树重取
            if (!root.refresh()) {
                root.recycle();
                root = rootWithRetry();
                if (root == null) return "screen dump failed: no active window (accessibility, refreshed)";
            }
            String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "unknown";
            StringBuilder entries = new StringBuilder(1024);
            int[] screen = {0, 0};
            int[] count = {0};
            walk(root, entries, screen, count);
            StringBuilder out = new StringBuilder(entries.length() + 64);
            out.append("app=").append(pkg).append('\n');
            if (screen[0] > 0 && screen[1] > 0) out.append("screen=").append(screen[0]).append('x').append(screen[1]).append('\n');
            out.append(entries);
            String s = out.toString().trim();
            return s.length() > DUMP_MAX_CHARS ? s.substring(0, DUMP_MAX_CHARS) : s;
        } catch (Throwable t) {
            Log.e(TAG, "screenDump", t);
            return "screen dump failed: " + t.getMessage();
        } finally {
            root.recycle();
        }
    }

    /** 按屏幕文本点击：节点树中查找 text/content-desc 包含目标的节点并点其中心。 */
    public static String tapText(String text) {
        if (text == null || text.isEmpty()) return "text required for tap_text";
        Rect b = findNodeByText(text);
        if (b == null) return "text not found on screen: " + text;
        return tap(b.centerX(), b.centerY());
    }

    /** 点赞当前视频：定位未点赞节点（含 点赞/喜欢/like/favorite 且不含「已」）。 */
    public static String likeOnce() {
        AccessibilityNodeInfo node = findLikeNode(false);
        if (node == null) {
            return hasLikedNode() ? "已点赞" : "未找到点赞按钮";
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        node.recycle();
        return tap(bounds.centerX(), bounds.centerY());
    }

    /** 连续点赞 N 条视频：每条点赞后上滑切到下一视频（N 上限 50，防止长时间占用通道）。 */
    public static String likeVideos(int n) {
        int count = Math.max(1, Math.min(n, 50));
        int liked = 0;
        for (int i = 0; i < count; i++) {
            String r = likeOnce();
            if (r.startsWith("未找到") || r.startsWith("读取屏幕")) {
                return "已点赞 " + liked + " 条视频（第 " + (i + 1) + " 条时" + r + "）";
            }
            liked++;
            if (i < count - 1) {
                int w = 1080, h = 2400;
                try {
                    String[] p = screenSize().split("x");
                    if (p.length == 2) {
                        w = Integer.parseInt(p[0].trim());
                        h = Integer.parseInt(p[1].trim());
                    }
                } catch (Exception ignored) {}
                swipe(w / 2, (int) (h * 0.8), w / 2, (int) (h * 0.3), 300L);
                sleepQuiet(1200);
            }
        }
        return "已点赞 " + liked + " 条视频";
    }

    // ---------- 内部实现 ----------

    private static boolean gesture(long durationMs, int x1, int y1, int x2, int y2) {
        try {
            AccessibilityControlService svc = AccessibilityControlService.instance();
            if (svc == null) return false;
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2 == x1 && y2 == y1 ? x1 + 1 : x2, y2 == y1 ? y1 + 1 : y2);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, durationMs);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return svc.dispatchGesture(g, null, null);
        } catch (Throwable t) {
            Log.d(TAG, "gesture: " + t.getMessage());
            return false;
        }
    }

    private static boolean globalAction(int action) {
        AccessibilityControlService svc = AccessibilityControlService.instance();
        return svc != null && svc.performGlobalAction(action);
    }

    /** 统一获取活动窗口根节点：窗口过渡期 root 可能为 null，最多重试 3 次（间隔 300ms）。 */
    private static AccessibilityNodeInfo rootWithRetry() {
        AccessibilityControlService svc = AccessibilityControlService.instance();
        if (svc == null) return null;
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) return root;
            sleepQuiet(300);
        }
        return null;
    }

    private static boolean setText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** 当前焦点输入框；无焦点时遍历找 isFocused 的输入框，找不到返回 null。 */
    private static AccessibilityNodeInfo focusedNode() {
        AccessibilityNodeInfo root = rootWithRetry();
        if (root == null) return null;
        try {
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null) {
                if (focus.isEditable()) return focus;
                focus.recycle();
            }
            return findFocusedEditable(root);
        } finally {
            root.recycle();
        }
    }

    private static AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo r = findFocusedEditable(child);
                child.recycle();
                if (r != null) return r;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditableNode() {
        AccessibilityNodeInfo root = rootWithRetry();
        if (root == null) return null;
        AccessibilityNodeInfo r = findEditableNode(root);
        root.recycle();
        return r;
    }

    private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() || isEditText(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo r = findEditableNode(child);
                child.recycle();
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 坐标命中最深层可点节点（低版本无手势注入时的点击回退）。 */
    private static AccessibilityNodeInfo nodeAt(int x, int y) {
        AccessibilityNodeInfo root = rootWithRetry();
        if (root == null) return null;
        try {
            return nodeAt(root, x, y);
        } finally {
            root.recycle();
        }
    }

    private static AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo node, int x, int y) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo hit = nodeAt(child, x, y);
                child.recycle();
                if (hit != null) return hit;
            }
        }
        return node.isClickable() ? node : null;
    }

    /** 遍历节点树生成读屏摘要（文本/描述/标志/坐标），与无线调试通道格式一致。 */
    private static void walk(AccessibilityNodeInfo node, StringBuilder sb, int[] screen, int[] count) {
        if (node == null || count[0] >= DUMP_LIMIT) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.right > screen[0]) screen[0] = bounds.right;
        if (bounds.bottom > screen[1]) screen[1] = bounds.bottom;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String text = t != null ? t.toString().trim() : "";
        String desc = d != null ? d.toString().trim() : "";
        boolean clickable = node.isClickable();
        boolean editable = node.isEditable() || isEditText(node);
        boolean scrollable = node.isScrollable();
        if ((!text.isEmpty() || !desc.isEmpty() || clickable || scrollable || editable) && count[0] < DUMP_LIMIT) {
            count[0]++;
            sb.append("- ");
            if (!text.isEmpty()) sb.append('"').append(ellipsize(text)).append('"');
            if (!desc.isEmpty()) {
                if (!text.isEmpty()) sb.append(' ');
                sb.append('[').append(ellipsize(desc)).append(']');
            }
            StringBuilder flags = new StringBuilder();
            if (clickable) flags.append(" clickable");
            if (editable) flags.append(" editable");
            if (scrollable) flags.append(" scrollable");
            if (flags.length() > 0) sb.append(flags);
            if (bounds.right > bounds.left && bounds.bottom > bounds.top) {
                sb.append(" bounds=(").append(bounds.left).append(',').append(bounds.top).append(',')
                  .append(bounds.right).append(',').append(bounds.bottom).append(')');
            }
            sb.append('\n');
        }
        for (int i = 0; i < node.getChildCount() && count[0] < DUMP_LIMIT; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, sb, screen, count);
                child.recycle();
            }
        }
    }

    private static boolean isEditText(AccessibilityNodeInfo node) {
        CharSequence cn = node.getClassName();
        return cn != null && "android.widget.EditText".equals(cn.toString());
    }

    private static Rect findNodeByText(String target) {
        AccessibilityNodeInfo root = rootWithRetry();
        if (root == null) return null;
        try {
            return findNodeByText(root, target.toLowerCase(Locale.ROOT));
        } finally {
            root.recycle();
        }
    }

    private static Rect findNodeByText(AccessibilityNodeInfo node, String lower) {
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String hay = ((t == null ? "" : t) + " " + (d == null ? "" : d)).toLowerCase(Locale.ROOT);
        if (hay.contains(lower)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.right > bounds.left && bounds.bottom > bounds.top) {
                node.recycle();
                return bounds;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                Rect r = findNodeByText(child, lower);
                child.recycle();
                if (r != null) return r;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findLikeNode(boolean liked) {
        AccessibilityNodeInfo root = rootWithRetry();
        if (root == null) return null;
        try {
            return findLikeNode(root, liked);
        } finally {
            root.recycle();
        }
    }

    private static AccessibilityNodeInfo findLikeNode(AccessibilityNodeInfo node, boolean liked) {
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        CharSequence rid = node.getViewIdResourceName();
        String hay = ((t == null ? "" : t) + " " + (d == null ? "" : d) + " " + (rid == null ? "" : rid))
            .toLowerCase(Locale.ROOT);
        boolean contains = hay.contains("点赞") || hay.contains("喜欢")
            || hay.contains("like") || hay.contains("favorite");
        if (contains && liked == hay.contains("已")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo r = findLikeNode(child, liked);
                child.recycle();
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean hasLikedNode() {
        AccessibilityNodeInfo n = findLikeNode(true);
        if (n == null) return false;
        n.recycle();
        return true;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String ellipsize(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }
}