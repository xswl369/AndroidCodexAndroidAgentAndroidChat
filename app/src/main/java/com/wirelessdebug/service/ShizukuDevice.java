package com.wirelessdebug.service;

import android.graphics.Rect;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 免权限手机控制实现：全部基于 shell 命令（input/am/uiautomator/cmd/pm/dumpsys/wm），
 * 无需开启无障碍服务即可点击、滑动、输入、按键、读屏、打开应用。
 * 读屏链路（不依赖截屏/OCR）：无障碍节点树 -> uiautomator dump -> dumpsys 文本 -> 窗口视图 -> 焦点窗口。
 */
public class ShizukuDevice {
    private static final String TAG = "ShizukuDevice";
    private static final String DUMP_PATH = "/sdcard/codex_ui.xml";
    public static String tap(int x, int y) {
        ShizukuController.ExecResult r = ShizukuController.exec("input", "tap", String.valueOf(x), String.valueOf(y));
        return r.ok ? "tapped (" + x + "," + y + ")" : "tap failed: " + r.output.trim();
    }

    public static String swipe(int x1, int y1, int x2, int y2, long durationMs) {
        ShizukuController.ExecResult r = ShizukuController.exec("input", "swipe",
            String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
        return r.ok ? "swiped" : "swipe failed: " + r.output.trim();
    }

    public static String back() { return keyEvent("4"); }
    public static String home() { return keyEvent("3"); }
    public static String recents() { return keyEvent("187"); }

    public static String keyEvent(String code) {
        ShizukuController.ExecResult r = ShizukuController.exec("input", "keyevent", code);
        return r.ok ? "keyevent " + code + " sent" : "keyevent failed: " + r.output.trim();
    }

    public static String notifications() {
        ShizukuController.ExecResult r = ShizukuController.exec("cmd", "statusbar", "expand-notifications");
        return r.ok ? "notifications expanded" : "notifications failed: " + r.output.trim();
    }

    /** 向当前聚焦输入框输入文本（ASCII 直接输入；含中文等非 ASCII 时返回提示，由模型改用粘贴/点选方案）。 */
    public static String inputText(String text) {
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
     * 条目不足时补充 dumpsys 文本 / 窗口视图 / 当前焦点。全程无截屏、无 OCR。
     */
    public static String screenDump() {
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
        ShizukuController.ExecResult d = ShizukuController.exec("uiautomator", "dump", DUMP_PATH);
        if (!d.ok) return "screen dump failed: " + d.output.trim();
        ShizukuController.ExecResult c = ShizukuController.exec("cat", DUMP_PATH);
        if (!c.ok) return "read dump failed: " + c.output.trim();
        String parsed = parseUiXml(c.output);
        return parsed == null ? "" : parsed.trim();
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
        if (text == null || text.isEmpty()) return "text required for tap_text";
        Rect b = null;
        ShizukuController.ExecResult d = ShizukuController.exec("uiautomator", "dump", DUMP_PATH);
        if (d.ok) {
            ShizukuController.ExecResult c = ShizukuController.exec("cat", DUMP_PATH);
            if (c.ok) b = findNodeByText(c.output, text);
        }
        if (b != null) return tap(b.centerX(), b.centerY());
        return "text not found on screen: " + text;
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
