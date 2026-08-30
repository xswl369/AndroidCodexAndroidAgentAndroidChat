package com.wirelessdebug.service;

import android.graphics.Rect;
import android.graphics.Point;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 手机控制统一门面：优先 shell 通道（Root → 无线调试 → Shizuku，命令 input/am/uiautomator/cmd/pm/dumpsys/wm），
 * 均不可用时自动回退无障碍通道（免 Root，与无线调试二选一，读屏同样不依赖截屏/OCR）。
 * 读屏链路（不依赖截屏/OCR）：无障碍节点树 -> uiautomator dump -> dumpsys 文本 -> 窗口视图 -> 焦点窗口。
 */
public class ShizukuDevice {
    private static final String TAG = "ShizukuDevice";
    private static final String DUMP_PATH = "/sdcard/codex_ui.xml";
    public static String tap(int x, int y) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.tap(x, y);
        ShizukuController.ExecResult r = ShizukuController.exec("input", "tap", String.valueOf(x), String.valueOf(y));
        return r.ok ? "tapped (" + x + "," + y + ")" : "tap failed: " + r.output.trim();
    }

    public static String swipe(int x1, int y1, int x2, int y2, long durationMs) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.swipe(x1, y1, x2, y2, durationMs);
        ShizukuController.ExecResult r = ShizukuController.exec("input", "swipe",
            String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
        return r.ok ? "swiped" : "swipe failed: " + r.output.trim();
    }

    /** 长按（坐标）：同点按住 durationMs，input swipe 原地停留实现。 */
    public static String longPress(int x, int y, long durationMs) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.longPress(x, y, durationMs);
        ShizukuController.ExecResult r = ShizukuController.exec("input", "swipe",
            String.valueOf(x), String.valueOf(y), String.valueOf(x), String.valueOf(y), String.valueOf(durationMs));
        return r.ok ? "long-pressed (" + x + "," + y + ")" : "long press failed: " + r.output.trim();
    }

    /** 双击（坐标）：两次快速点击，间隔 80ms。 */
    public static String doubleTap(int x, int y) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.doubleTap(x, y);
        String first = tap(x, y);
        sleepQuiet(80);
        String second = tap(x, y);
        return first + " then " + second;
    }

    public static String back() {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.back();
        return keyEvent("4");
    }
    public static String home() {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.home();
        return keyEvent("3");
    }
    public static String recents() {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.recents();
        return keyEvent("187");
    }

    public static String keyEvent(String code) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.keyEvent(code);
        ShizukuController.ExecResult r = ShizukuController.exec("input", "keyevent", code);
        return r.ok ? "keyevent " + code + " sent" : "keyevent failed: " + r.output.trim();
    }

    public static String notifications() {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.notifications();
        ShizukuController.ExecResult r = ShizukuController.exec("cmd", "statusbar", "expand-notifications");
        return r.ok ? "notifications expanded" : "notifications failed: " + r.output.trim();
    }

    /** 向当前聚焦输入框输入文本（ASCII 直接输入；含中文等非 ASCII 时返回提示，由模型改用粘贴/点选方案）。 */
    public static String inputText(String text) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.inputText(text);
        if (text == null || text.isEmpty()) return "input text required";
        if (!isAscii(text)) {
            return "input text only supports ASCII; non-ASCII text needs clipboard/selection approach";
        }
        ShizukuController.ExecResult r = ShizukuController.exec("input", "text", text);
        return r.ok ? "input sent" : "input failed: " + r.output.trim();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /** 打开应用：先解析默认入口组件再 am start（monkey/裸包名易被权限限制），失败兜底 monkey。 */
    public static String openApp(String pkg) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.openApp(pkg);
        if (pkg == null || pkg.trim().isEmpty()) return "package required";
        String p = pkg.trim();
        ShizukuController.ExecResult ra = ShizukuController.exec("cmd", "package", "resolve-activity", "--brief", p);
        if (ra.ok && ra.output != null && !ra.output.trim().isEmpty()) {
            for (String ln : ra.output.split("\n")) {
                String comp = ln.trim();
                if (comp.contains("/") && !comp.contains(" ")) {
                    ShizukuController.ExecResult r = ShizukuController.exec("am", "start", "-n", comp);
                    if (r.ok) return "opened: " + comp;
                    return "open failed: " + r.output.trim();
                }
            }
        }
        ShizukuController.ExecResult r = ShizukuController.exec("monkey", "-p", p, "1");
        return r.ok ? "opened: " + p : "open failed: " + r.output.trim();
    }

    /** 列出已启用应用（含系统自带），备用路径；主路径由 DeviceControlTool 用 PackageManager 枚举。 */
    public static String listApps() {
        ShizukuController.ExecResult r = ShizukuController.exec("pm", "list", "packages", "-e");
        if (!r.ok) return "list apps failed: " + r.output.trim();
        StringBuilder sb = new StringBuilder();
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) sb.append(t.substring("package:".length())).append('\n');
        }
        return sb.length() > 0 ? sb.toString().trim() : "no enabled apps";
    }

    /** 读取屏幕物理尺寸，格式 WxH；失败返回 unknown。 */
    public static String screenSize() {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.screenSize();
        ShizukuController.ExecResult r = ShizukuController.exec("wm", "size");
        if (!r.ok || r.output == null) return "unknown";
        for (String ln : r.output.split("\n")) {
            String t = ln.trim().toLowerCase();
            if (t.startsWith("physical size:")) {
                String[] p = t.replace("physical size:", "").trim().split("[xX]");
                if (p.length == 2) return p[0].trim() + "x" + p[1].trim();
            }
        }
        return "unknown";
    }

    /**
     * 读取当前屏幕。优先无障碍节点快照（瞬时、自绘界面可用），其次 uiautomator dump（坐标最全），
     * 条目不足时补充 dumpsys 文本 / 窗口视图 / 当前焦点，最后兜底像素级 OCR（截屏 + 中文/英文识别）。
     * 双通道互备：无障碍失败自动回退 shell，shell 失败自动回退像素 OCR；节点树优先、像素兜底。
     */
    public static String screenDump() {
        // 无障碍可用时优先（内存节点树实时、秒出），失败回退 shell
        String a11y = null;
        if (AccessibilityDevice.isEnabled() && AccessibilityDevice.isConnected()) {
            a11y = AccessibilityDevice.screenDump();
            if (!a11y.startsWith("screen dump failed") && countEntries(a11y) > 0) return a11y;
        }
        String shell = shellScreenDump();
        if (!shell.startsWith("screen dump failed") && countEntries(shell) > 0) return shell;
        // 像素级读屏兜底：节点树全失败/无条目时，截屏 OCR 仍能识别自绘/无节点界面
        String pixel = ScreenOcr.ocrScreenFull();
        if (!pixel.startsWith("screen dump failed") && countEntries(pixel) > 0) return pixel;
        return "screen dump failed: a11y[" + (a11y == null ? "disabled" : a11y)
            + "] shell[" + shell + "] pixel[" + pixel + "]";
    }

    /** shell 通道读屏：uiautomator dump 为主，dumpsys 文本/窗口视图/焦点兜底。 */
    private static String shellScreenDump() {
        String s = "";
        // 1) uiautomator dump：标准界面坐标最全
        String ui = uiAutomatorDump();
        if (ui != null && countEntries(ui) >= 3) s = ui.trim();
        // 2) 兜底补充：dumpsys 文本 / 窗口视图层级 / 当前焦点
        if (countEntries(s) < 3) {
            StringBuilder sb = new StringBuilder(s.isEmpty() ? "" : s + "\n");
            String top = activityTopText();
            if (!top.isEmpty()) sb.append("\n--- window texts ---\n").append(top);
            String views = windowViewsDump();
            if (!views.isEmpty()) sb.append("\n--- view hierarchy ---\n").append(views);
            s = sb.toString().trim();
        }
        String focus = currentFocusLine();
        if (!focus.isEmpty()) s = focus + "\n" + s;
        return s.length() > 6000 ? s.substring(0, 6000) : s;
    }

    private static String uiAutomatorDump() {
        // 打开应用/过渡动画期间系统未 idle，uiautomator dump 常失败：重试 3 次（间隔 500ms）
        for (int attempt = 0; attempt < 3; attempt++) {
            ShizukuController.ExecResult d = ShizukuController.exec("uiautomator", "dump", DUMP_PATH);
            if (d.ok) {
                ShizukuController.ExecResult c = ShizukuController.exec("cat", DUMP_PATH);
                if (c.ok) {
                    String parsed = parseUiXml(c.output);
                    if (parsed != null && !parsed.startsWith("screen parse failed") && countEntries(parsed) > 0) {
                        return parsed.trim();
                    }
                }
            }
            sleepQuiet(500);
        }
        return "screen dump failed: uiautomator dump unavailable after retries";
    }

    /** 统计读屏条目数量（- "..." 行）。 */
    private static int countEntries(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (String line : s.split("\n")) {
            if (line.startsWith("- ")) n++;
        }
        return n;
    }

    /** dumpsys activity top 提取可见 TextView/EditText 文本（部分 ROM 输出含 Text= 字段）。 */
    private static String activityTopText() {
        try {
            ShizukuController.ExecResult r = ShizukuController.exec("dumpsys", "activity", "top");
            if (!r.ok || r.output == null) return "";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String ln : r.output.split("\n")) {
                int i1 = ln.indexOf("Text=");
                if (i1 < 0) continue;
                String seg = ln.substring(i1 + 5);
                int i2 = seg.indexOf("}");
                if (i2 < 0) i2 = seg.length();
                String text = seg.substring(0, i2).trim();
                if (text.isEmpty() || "null".equals(text)) continue;
                if (n++ >= 40) break;
                sb.append("- \"").append(ellipsize(text)).append("\"\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.d(TAG, "activityTopText: " + t.getMessage());
            return "";
        }
    }

    /**
     * Android 14+ 的 cmd window dump-visible-window-views：输出窗口视图层级。
     * 提取文本视图/按钮行（含 text= 时取文本），供自绘界面补充上下文。
     */
    private static String windowViewsDump() {
        try {
            ShizukuController.ExecResult r = ShizukuController.exec("cmd", "window", "dump-visible-window-views");
            if (!r.ok || r.output == null || r.output.trim().isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String ln : r.output.split("\n")) {
                String t = ln.trim();
                if (!t.contains("TextView") && !t.contains("Button") && !t.contains("EditText")) continue;
                String text = extractViewText(t);
                if (!text.isEmpty()) {
                    sb.append("- \"").append(ellipsize(text)).append("\"\n");
                } else {
                    sb.append("- ").append(t.length() > 120 ? t.substring(0, 120) : t).append('\n');
                }
                if (++n >= 60) break;
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.d(TAG, "windowViewsDump: " + t.getMessage());
            return "";
        }
    }

    private static String extractViewText(String line) {
        // 兼容多种输出格式：text="xxx" / text: xxx / mText=xxx
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:text\\s*[=:]\\s*\"?([^\",}]+)\"?)|(?:mText\\s*=\\s*([^,}]+))").matcher(line);
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /** dumpsys window 提取当前焦点窗口（模型判断当前所处应用/页面）。 */
    private static String currentFocusLine() {
        try {
            ShizukuController.ExecResult r = ShizukuController.exec("dumpsys", "window");
            if (!r.ok || r.output == null) return "";
            for (String ln : r.output.split("\n")) {
                String t = ln.trim();
                if (t.startsWith("mCurrentFocus") || t.startsWith("mFocusedApp")) {
                    return t.replaceAll("\\s+", " ").trim();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 按文本查找节点并点击（基于屏幕 XML + 无障碍节点树，无截图）。 */
    public static String tapText(String text) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.tapText(text);
        if (text == null || text.isEmpty()) return "text required for tap_text";
        Rect b = null;
        ShizukuController.ExecResult d = ShizukuController.exec("uiautomator", "dump", DUMP_PATH);
        if (d.ok) {
            ShizukuController.ExecResult c = ShizukuController.exec("cat", DUMP_PATH);
            if (c.ok) b = findNodeByText(c.output, text);
        }
        if (b != null) return tap(b.centerX(), b.centerY());
        // 节点树未命中：像素 OCR 定位兜底（中文/英文，截屏 + 识别）
        Point p = ScreenOcr.findTextPoint(text);
        if (p != null) return tap(p.x, p.y);
        return "text not found on screen: " + text;
    }

    /** 读取 uiautomator dump 原始 XML（先 dump 到 /sdcard 再 cat 回来）。 */
    private static String uiXml() {
        ShizukuController.ExecResult d = ShizukuController.exec("uiautomator", "dump", DUMP_PATH);
        if (!d.ok) return null;
        ShizukuController.ExecResult c = ShizukuController.exec("cat", DUMP_PATH);
        return c.ok ? c.output : null;
    }

    /** 点赞当前视频：在 UI 树定位未点赞节点（优先「点赞/喜欢/like」，跳过已点赞态），点击一次。 */
    public static String likeOnce() {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.likeOnce();
        String xml = uiXml();
        if (xml == null) return "读取屏幕节点失败";
        Rect b = findUnlikedNode(xml);
        if (b == null) return hasLikedNode(xml) ? "已点赞" : "未找到点赞按钮";
        return tap(b.centerX(), b.centerY());
    }

    /** 连续点赞 N 条视频：每条点赞后上滑切到下一视频（N 上限 50，防止误触长时间占用通道）。 */
    public static String likeVideos(int n) {
        if (AccessibilityDevice.isActiveChannel()) return AccessibilityDevice.likeVideos(n);
        int count = Math.max(1, Math.min(n, 50));
        int liked = 0;
        for (int i = 0; i < count; i++) {
            String r = likeOnce();
            if (r.startsWith("未找到") || r.startsWith("读取屏幕")) {
                return "已点赞 " + liked + " 条视频（第 " + (i + 1) + " 条时" + r + "）";
            }
            liked++;
            if (i < count - 1) {
                swipeUpHalf();
                sleepQuiet(1200);
            }
        }
        return "已点赞 " + liked + " 条视频";
    }

    /** 上滑半屏（抖音/小红书等竖滑信息流切下一条视频）。 */
    private static void swipeUpHalf() {
        String size = screenSize();
        int w = 1080, h = 2400;
        try {
            String[] p = size.split("x");
            if (p.length == 2) {
                w = Integer.parseInt(p[0].trim());
                h = Integer.parseInt(p[1].trim());
            }
        } catch (Exception ignored) {}
        swipe(w / 2, (int) (h * 0.8), w / 2, (int) (h * 0.3), 300L);
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** 定位未点赞节点：text/content-desc/resource-id 含 点赞/喜欢/like/favorite 且不含「已」（避免误点导致取消点赞）。 */
    private static Rect findUnlikedNode(String xml) {
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(new StringReader(xml));
            while (xp.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xp.getEventType() == XmlPullParser.START_TAG && "node".equals(xp.getName())) {
                    String t = xp.getAttributeValue(null, "text");
                    String desc = xp.getAttributeValue(null, "content-desc");
                    String res = xp.getAttributeValue(null, "resource-id");
                    Rect r = parseBounds(xp.getAttributeValue(null, "bounds"));
                    if (r != null) {
                        String hay = ((t == null ? "" : t) + " " + (desc == null ? "" : desc) + " " + (res == null ? "" : res))
                            .toLowerCase(java.util.Locale.US);
                        if (containsLike(hay) && !hay.contains("已")) return r;
                    }
                }
                xp.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "findUnlikedNode", e);
        }
        return null;
    }

    /** 屏幕是否存在已点赞节点（用于把「已点赞」的视频计为点赞成功而不重复点击）。 */
    private static boolean hasLikedNode(String xml) {
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(new StringReader(xml));
            while (xp.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xp.getEventType() == XmlPullParser.START_TAG && "node".equals(xp.getName())) {
                    String t = xp.getAttributeValue(null, "text");
                    String desc = xp.getAttributeValue(null, "content-desc");
                    String res = xp.getAttributeValue(null, "resource-id");
                    if (parseBounds(xp.getAttributeValue(null, "bounds")) != null) {
                        String hay = ((t == null ? "" : t) + " " + (desc == null ? "" : desc) + " " + (res == null ? "" : res))
                            .toLowerCase(java.util.Locale.US);
                        if (containsLike(hay) && hay.contains("已")) return true;
                    }
                }
                xp.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "hasLikedNode", e);
        }
        return false;
    }

    private static boolean containsLike(String hay) {
        return hay.contains("点赞") || hay.contains("喜欢")
            || hay.contains("like") || hay.contains("favorite");
    }

    private static String parseUiXml(String xml) {
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(new StringReader(xml));
            StringBuilder sb = new StringBuilder(1024);
            String pkg = "unknown";
            int screenW = 0, screenH = 0;
            int[] count = {0};
            while (xp.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xp.getEventType() == XmlPullParser.START_TAG && "node".equals(xp.getName())) {
                    String t = xp.getAttributeValue(null, "text");
                    String desc = xp.getAttributeValue(null, "content-desc");
                    String bnd = xp.getAttributeValue(null, "bounds");
                    String clickable = xp.getAttributeValue(null, "clickable");
                    String scrollable = xp.getAttributeValue(null, "scrollable");
                    String editable = xp.getAttributeValue(null, "editable");
                    if ("unknown".equals(pkg) && xp.getAttributeValue(null, "package") != null)
                        pkg = xp.getAttributeValue(null, "package");
                    Rect rect = parseBounds(bnd);
                    if (rect != null && screenW == 0 && screenH == 0) {
                        screenW = rect.right; screenH = rect.bottom;
                    }
                    boolean hasText = t != null && !t.isEmpty();
                    boolean hasDesc = desc != null && !desc.isEmpty();
                    boolean actionable = "true".equals(clickable) || "true".equals(scrollable) || "true".equals(editable);
                    if ((hasText || hasDesc || actionable) && count[0] < 220) {
                        count[0]++;
                        sb.append("- ");
                        if (hasText) sb.append('"').append(ellipsize(t)).append('"');
                        if (hasDesc) { if (hasText) sb.append(' '); sb.append('[').append(ellipsize(desc)).append(']'); }
                        StringBuilder flags = new StringBuilder();
                        if ("true".equals(clickable)) flags.append(" clickable");
                        if ("true".equals(editable)) flags.append(" editable");
                        if ("true".equals(scrollable)) flags.append(" scrollable");
                        if (flags.length() > 0) sb.append(flags);
                        if (rect != null) sb.append(" bounds=(").append(rect.left).append(',').append(rect.top).append(',')
                            .append(rect.right).append(',').append(rect.bottom).append(')');
                        sb.append('\n');
                    }
                }
                xp.next();
            }
            StringBuilder out = new StringBuilder();
            out.append("app=").append(pkg).append('\n');
            if (screenW > 0 && screenH > 0) out.append("screen=").append(screenW).append('x').append(screenH).append('\n');
            out.append(sb);
            String s = out.toString().trim();
            return s.length() > 6000 ? s.substring(0, 6000) : s;
        } catch (Exception e) {
            Log.e(TAG, "parseUiXml", e);
            return "screen parse failed: " + e.getMessage();
        }
    }

    private static Rect findNodeByText(String xml, String text) {
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(new StringReader(xml));
            String target = text.toLowerCase();
            while (xp.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xp.getEventType() == XmlPullParser.START_TAG && "node".equals(xp.getName())) {
                    String t = xp.getAttributeValue(null, "text");
                    String desc = xp.getAttributeValue(null, "content-desc");
                    if ((t != null && t.toLowerCase().contains(target)) || (desc != null && desc.toLowerCase().contains(target))) {
                        Rect r = parseBounds(xp.getAttributeValue(null, "bounds"));
                        if (r != null) return r;
                    }
                }
                xp.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "findNodeByText", e);
        }
        return null;
    }

    private static Rect parseBounds(String bounds) {
        if (bounds == null) return null;
        try {
            String[] parts = bounds.split("\\]\\[");
            if (parts.length != 2) return null;
            String[] p1 = parts[0].replace("[", "").split(",");
            String[] p2 = parts[1].replace("]", "").split(",");
            if (p1.length != 2 || p2.length != 2) return null;
            return new Rect(Integer.parseInt(p1[0].trim()), Integer.parseInt(p1[1].trim()),
                Integer.parseInt(p2[0].trim()), Integer.parseInt(p2[1].trim()));
        } catch (Exception e) { return null; }
    }

    private static String ellipsize(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }
}
