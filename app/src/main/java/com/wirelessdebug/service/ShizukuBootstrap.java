package com.wirelessdebug.service;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 内置引导：把随 APK 内置的 Shizuku 资产（manager APK、starter 二进制、start.sh）
 * 释放到应用私有目录，供电脑端脚本提取使用；并在应用启动时自动检查 server 与授权状态。
 */
public class ShizukuBootstrap {
    private static final String TAG = "ShizukuBootstrap";
    public static final String ASSET_DIR = "shizuku";
    public static final String MANAGER_APK = "shizuku_manager.apk";
    public static final String STARTER = "shizuku_starter";
    public static final String START_SH = "start.sh";
    public static final String ADB_KEY = "adbkey";
    public static final String ADB_CERT = "adbcert.pem";
    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQ_CODE = 10086;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 释放内置资产到 files/shizuku/（幂等，按文件大小判断是否需要重新释放）。 */
    public static void ensureAssets(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), ASSET_DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            copyIfNeeded(ctx, MANAGER_APK, new File(dir, MANAGER_APK));
            copyIfNeeded(ctx, STARTER, new File(dir, STARTER));
            copyIfNeeded(ctx, START_SH, new File(dir, START_SH));
            copyIfNeeded(ctx, ADB_KEY, new File(dir, ADB_KEY));
            copyIfNeeded(ctx, ADB_CERT, new File(dir, ADB_CERT));
            Log.d(TAG, "assets ready at " + dir.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "ensureAssets failed", t);
        }
    }

    private static void copyIfNeeded(Context ctx, String asset, File dst) throws Exception {
        if (dst.exists() && dst.length() > 0) {
            InputStream cur = ctx.getAssets().open(ASSET_DIR + "/" + asset);
            int curLen = cur.available();
            cur.close();
            if (dst.length() == curLen) return;
        }
        InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + asset);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
        dst.setExecutable(true, true);
        Log.d(TAG, "asset released: " + asset);
    }

    /** 内置的 Shizuku Manager 是否已安装到设备。 */
    public static boolean isManagerInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Shizuku server 是否运行。 */
    public static boolean isServerRunning() {
        return ShizukuController.isAvailable();
    }

    /** 是否已获得 Shizuku 授权。 */
    public static boolean hasPermission() {
        return ShizukuController.hasPermission();
    }

    /** 自动请求授权：server 可用且未授权时弹出系统授权框。 */
    public static void ensurePermission() {
        try {
            if (!isServerRunning() || hasPermission()) return;
            if (Shizuku.shouldShowRequestPermissionRationale()) return;
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) {
            Log.e(TAG, "ensurePermission failed", t);
        }
    }

    /** 启动时自动处理：释放资产 + 自动尝试启动 Shizuku server + 请求授权。 */
    public static void onAppStart(final Context ctx) {
        ensureAssets(ctx);
        MAIN.postDelayed(() -> {
            if (ShizukuController.isAvailable() && ShizukuController.hasPermission()) return;
            ShizukuAutoStart.tryStart(ctx, (ok, msg) -> MAIN.post(() -> {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                if (ok) ensurePermission();
            }));
        }, 1200);
    }
}



