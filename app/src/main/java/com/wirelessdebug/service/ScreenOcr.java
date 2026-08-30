
package com.wirelessdebug.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.wirelessdebug.WdbContext;
import com.wirelessdebug.PairState;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 屏幕 OCR 工具：识别配对弹窗中的 IP:端口 与 6 位配对码。
 * 识别前先裁剪中央区域 + Otsu 二值化，解决遮罩/低对比度/暗色主题导致的空识别；
 * 单例初始化 Tesseract，避免预热线程与轮询线程并发 init 互相卡死。
 */
public class ScreenOcr {
    private static final String TAG = "ScreenOcr";
    private static volatile TessBaseAPI tess;
    private static final Object TESS_LOCK = new Object();
    private static volatile long lastInitFailMs;

    /** 预热 OCR：提取 traineddata + 初始化 Tesseract，消除首次识别延迟。 */
    public static void prepare() {
        try {
            getTess();
        } catch (Throwable t) {
            Log.d(TAG, "prepare: " + t.getMessage());
        }
    }

    public static String captureAndOcr() {
        try {
            Bitmap bmp = captureBitmap();
            return bmp != null ? recognize(bmp) : null;
        } catch (Throwable t) {
            Log.d(TAG, "captureAndOcr: " + t.getMessage());
            return null;
        }
    }

    /** screencap 转 base64 再转 Bitmap（需 Shizuku 权限）。 */
    public static Bitmap captureBitmap() {
        try {
            ShizukuController.ExecResult cap = ShizukuController.exec("screencap", "-p", "/sdcard/codex_screen.png");
            if (!cap.ok) return null;
            ShizukuController.ExecResult b64 = ShizukuController.exec("base64", "/sdcard/codex_screen.png");
            if (!b64.ok || b64.output == null) return null;
            String s = b64.output.replaceAll("\\s+", "");
            if (s.isEmpty()) return null;
            byte[] bytes = Base64.decode(s, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            Log.d(TAG, "captureBitmap: " + t.getMessage());
            return null;
        }
    }

    public static String recognize(Bitmap bmp) {
        TessBaseAPI t = getTess();
        if (t == null || bmp == null) return null;
        try {
            Bitmap frame = prepareFrame(bmp);
            // 两遍识别（常规黑字白底 / 反色白字深色底），合并时优先含 6 位数字的行（配对码特征）
            String txt = ocrPass(t, frame, false);
            String merged = txt == null ? "" : txt;
            if (!containsSixDigits(merged)) {
                String inv = ocrPass(t, frame, true);
                if (inv != null) merged = mergeOcr(merged, inv);
            }
            if (merged.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String line : merged.split("\n")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                if (n++ >= 40) break;
                sb.append("- \"").append(l.length() > 80 ? l.substring(0, 80) : l).append("\"\n");
            }
            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Throwable t2) {
            Log.d(TAG, "recognize: " + t2.getMessage());
            return null;
        }
    }

    /** 单遍 OCR：二值化后按数字白名单识别（配对码/端口只需数字与分隔符）。 */
    private static String ocrPass(TessBaseAPI t, Bitmap frame, boolean inverted) {
        Bitmap b = inverted ? binarizeInverted(frame) : binarize(frame);
        try {
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
            t.setVariable("tessedit_char_whitelist", "0123456789:./- ");
            t.setImage(b);
            return t.getUTF8Text();
        } catch (Throwable t2) {
            Log.d(TAG, "ocrPass: " + t2.getMessage());
            return null;
        } finally {
            if (b != frame) b.recycle();
        }
    }

    /** 合并两遍识别结果：按行去重，优先含 6 位数字的行，其次常规遍，最后反色遍。 */
    private static String mergeOcr(String normal, String inverted) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> sixDigits = new java.util.LinkedHashSet<>();
        for (String s : normal.split("\n")) {
            String l = s.trim();
            if (l.isEmpty()) continue;
            (containsSixDigits(l) ? sixDigits : out).add(l);
        }
        for (String s : inverted.split("\n")) {
            String l = s.trim();
            if (l.isEmpty() || out.contains(l) || sixDigits.contains(l)) continue;
            (containsSixDigits(l) ? sixDigits : out).add(l);
        }
        java.util.ArrayList<String> all = new java.util.ArrayList<>(sixDigits);
        all.addAll(out);
        return String.join("\n", all);
    }

    private static boolean containsSixDigits(String s) {
        return s != null && java.util.regex.Pattern.compile("\\d{6}").matcher(s).find();
    }

    /**
     * 截取屏幕中部偏下区域并缩放（配对弹窗在多数 ROM 居中，荣耀/华为偏下，
     * 裁剪 30%-92% 高度覆盖两种布局），大幅提速 LSTM OCR。
     */
    private static Bitmap prepareFrame(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return src;
            // 裁剪 84% 宽 x 中部偏下 62% 高，覆盖居中/偏下两种弹窗布局
            int x0 = (int) (w * 0.08f);
            int cw = (int) (w * 0.84f);
            int y0 = (int) (h * 0.30f);
            int chh = (int) (h * 0.62f);
            if (x0 + cw > w) cw = w - x0;
            if (y0 + chh > h) chh = h - y0;
            if (cw <= 0 || chh <= 0) return src;
            Bitmap crop = Bitmap.createBitmap(src, x0, y0, cw, chh);
            int targetW = Math.min(720, cw);
            if (crop.getWidth() > targetW) {
                int th = (int) (chh * (targetW / (float) crop.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(crop, targetW, th, true);
                if (scaled != crop) crop.recycle();
                return scaled;
            }
            return crop;
        } catch (Throwable t) {
            return src;
        }
    }

    /** Otsu 二值化：强对比提取前景文字（黑字白底），解决遮罩/低对比度/暗色主题导致的空识别。 */
    private static Bitmap binarize(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return src;
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            int[] gray = new int[px.length];
            int[] hist = new int[256];
            for (int i = 0; i < px.length; i++) {
                int g = ((px[i] >> 16 & 0xFF) * 299 + (px[i] >> 8 & 0xFF) * 587 + (px[i] & 0xFF) * 114) / 1000;
                gray[i] = g;
                hist[g]++;
            }
            // Otsu 求最佳阈值
            int total = px.length;
            float sum = 0;
            for (int i = 0; i < 256; i++) sum += (float) i * hist[i];
            float sumB = 0;
            int wB = 0;
            float maxVar = -1;
            int thr = 127;
            for (int i = 0; i < 256; i++) {
                wB += hist[i];
                if (wB == 0) continue;
                int wF = total - wB;
                if (wF == 0) break;
                sumB += (float) i * hist[i];
                float mB = sumB / wB;
                float mF = (sum - sumB) / wF;
                float v = (float) wB * wF * (mB - mF) * (mB - mF);
                if (v > maxVar) { maxVar = v; thr = i; }
            }
            int[] out = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int g = gray[i];
                out[i] = (g < thr) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return src;
        }
    }

    /** 反色 Otsu 二值化：深色主题白字时前景为亮像素（常规版反之）。 */
    private static Bitmap binarizeInverted(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return src;
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            int[] gray = new int[px.length];
            int[] hist = new int[256];
            for (int i = 0; i < px.length; i++) {
                int g = ((px[i] >> 16 & 0xFF) * 299 + (px[i] >> 8 & 0xFF) * 587 + (px[i] & 0xFF) * 114) / 1000;
                gray[i] = g;
                hist[g]++;
            }
            int total = px.length;
            float sum = 0;
            for (int i = 0; i < 256; i++) sum += (float) i * hist[i];
            float sumB = 0;
            int wB = 0;
            float maxVar = -1;
            int thr = 127;
            for (int i = 0; i < 256; i++) {
                wB += hist[i];
                if (wB == 0) continue;
                int wF = total - wB;
                if (wF == 0) break;
                sumB += (float) i * hist[i];
                float mB = sumB / wB;
                float mF = (sum - sumB) / wF;
                float v = (float) wB * wF * (mB - mF) * (mB - mF);
                if (v > maxVar) { maxVar = v; thr = i; }
            }
            int[] out = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int g = gray[i];
                out[i] = (g > thr) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return src;
        }
    }

    /** OCR 定位文本（英文/数字）并返回中心坐标；找不到返回 null。 */
    public static android.graphics.Point findTextPoint(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            Bitmap bmp = captureBitmap();
            if (bmp == null) return null;
            TessBaseAPI t = getTess();
            if (t == null) return null;
            t.setImage(bmp);
            String hocr = t.getHOCRText(0);
            if (hocr == null) return null;
            String target = text.trim().toLowerCase();
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "<span class='ocrx_word' title='bbox (\\d+) (\\d+) (\\d+) (\\d+)'>([^<]+)</span>");
            java.util.regex.Matcher m = pat.matcher(hocr);
            while (m.find()) {
                String word = m.group(5).trim().toLowerCase();
                if (word.contains(target) || target.contains(word)) {
                    int x1 = Integer.parseInt(m.group(1)), y1 = Integer.parseInt(m.group(2));
                    int x2 = Integer.parseInt(m.group(3)), y2 = Integer.parseInt(m.group(4));
                    return new android.graphics.Point((x1 + x2) / 2, (y1 + y2) / 2);
                }
            }
            return null;
        } catch (Throwable t2) {
            Log.d(TAG, "findTextPoint: " + t2.getMessage());
            return null;
        }
    }

    /**
     * 全屏像素 OCR（中文+英文）：截屏 → 逐行识别并输出带坐标的结构化文本，
     * 格式与节点树读屏一致（- "文本" bounds=(l,t,r,b)），供读屏融合与点击兜底。
     */
    public static String ocrScreenFull() {
        try {
            Bitmap bmp = captureBitmap();
            if (bmp == null) return "screen dump failed: screencap unavailable for pixel OCR";
            TessBaseAPI t = getTess();
            if (t == null) return "screen dump failed: OCR engine unavailable";
            Bitmap frame = prepareFullFrame(bmp);
            try {
                t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                t.setVariable("tessedit_char_whitelist", "");
                t.setImage(frame);
                StringBuilder sb = new StringBuilder(1024);
                int count = 0;
                com.googlecode.tesseract.android.ResultIterator ri = t.getResultIterator();
                try {
                    do {
                        String line = ri.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        android.graphics.Rect r = ri.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (line == null || line.trim().isEmpty() || r == null) continue;
                        if (r.right <= r.left || r.bottom <= r.top) continue;
                        // 缩放过需换算回原图坐标
                        float sx = bmp.getWidth() / (float) frame.getWidth();
                        float sy = bmp.getHeight() / (float) frame.getHeight();
                        int l = (int) (r.left * sx), t2 = (int) (r.top * sy);
                        int rr = (int) (r.right * sx), bb = (int) (r.bottom * sy);
                        sb.append("- \"").append(ellipsize(line.trim())).append("\" bounds=(")
                          .append(l).append(',').append(t2).append(',').append(rr).append(',').append(bb).append(")\n");
                        if (++count >= 120) break;
                    } while (ri.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));
                } finally {
                    try { ri.delete(); } catch (Throwable ignored) {}
                }
                String s = sb.toString().trim();
                return s.isEmpty() ? "screen dump failed: pixel OCR found no text" : s;
            } finally {
                if (frame != bmp) frame.recycle();
                bmp.recycle();
            }
        } catch (Throwable t) {
            Log.d(TAG, "ocrScreenFull: " + t.getMessage());
            return "screen dump failed: pixel OCR error: " + t.getMessage();
        }
    }

    /** 全屏 OCR 预处理：等比缩放到最长边 720，提速且不裁剪。 */
    private static Bitmap prepareFullFrame(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return src;
            int targetW = Math.min(720, w);
            if (w <= targetW) return src;
            int th = (int) (h * (targetW / (float) w));
            return Bitmap.createScaledBitmap(src, targetW, th, true);
        } catch (Throwable t) {
            return src;
        }
    }

    private static String ellipsize(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }

    private static TessBaseAPI getTess() {
        if (tess != null) return tess;
        // 单例初始化：避免预热线程与轮询线程并发 init 互相卡死
        synchronized (TESS_LOCK) {
            if (tess != null) return tess;
            // 初始化失败后冷却 5s，避免每帧重复耗时重试
            if (lastInitFailMs > 0 && System.currentTimeMillis() - lastInitFailMs < 5000) return null;
            try {
                java.io.File dir = new java.io.File(WdbContext.get().getFilesDir(), "tessdata");
                if (!dir.exists()) dir.mkdirs();
                String[] langs = { "eng", "chi_sim" };
                for (String lang : langs) {
                    java.io.File tf = new java.io.File(dir, lang + ".traineddata");
                    if (!tf.exists()) {
                        try (InputStream in = WdbContext.get().getAssets().open("tessdata/" + lang + ".traineddata");
                             FileOutputStream out = new FileOutputStream(tf)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                }
                TessBaseAPI t = new TessBaseAPI();
                if (!t.init(dir.getParent(), "eng+chi_sim", TessBaseAPI.OEM_LSTM_ONLY)) {
                    lastInitFailMs = System.currentTimeMillis();
                    return null;
                }
                tess = t;
                return t;
            } catch (Throwable t2) {
                lastInitFailMs = System.currentTimeMillis();
                Log.e(TAG, "init tess failed", t2);
                return null;
            }
        }
    }
}
