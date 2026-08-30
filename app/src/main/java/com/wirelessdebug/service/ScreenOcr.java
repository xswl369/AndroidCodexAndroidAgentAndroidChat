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

/** 屏幕 OCR 兜底：screencap → base64 → Tesseract(eng)，补充 uiautomator 读取不到的文字。 */
public class ScreenOcr {
    private static final String TAG = "ScreenOcr";
    private static volatile TessBaseAPI tess;

    /** 截屏并 OCR，返回可读文本行（- "text" 格式）；失败返回 null。 */
    public static String captureAndOcr() {
        try {
            Bitmap bmp = captureBitmap();
            return bmp != null ? recognize(bmp) : null;
        } catch (Throwable t) {
            Log.d(TAG, "captureAndOcr: " + t.getMessage());
            return null;
        }
    }

    /** screencap → base64 → Bitmap。 */
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
            t.setImage(bmp);
            String txt = t.getUTF8Text();
            if (txt == null) return null;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String line : txt.split("\n")) {
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

    private static TessBaseAPI getTess() {
        if (tess != null) return tess;
        try {
            java.io.File dir = new java.io.File(WdbContext.get().getFilesDir(), "tessdata");
            if (!dir.exists()) dir.mkdirs();
            java.io.File tf = new java.io.File(dir, "eng.traineddata");
            if (!tf.exists()) {
                try (InputStream in = WdbContext.get().getAssets().open("tessdata/eng.traineddata");
                     FileOutputStream out = new FileOutputStream(tf)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            TessBaseAPI t = new TessBaseAPI();
            if (!t.init(dir.getParent(), "eng", TessBaseAPI.OEM_LSTM_ONLY)) return null;
            tess = t;
            return t;
        } catch (Throwable t2) {
            Log.e(TAG, "init tess failed", t2);
            return null;
        }
    }
}
