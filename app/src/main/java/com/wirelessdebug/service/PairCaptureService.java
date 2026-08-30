package com.wirelessdebug.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.wirelessdebug.WdbContext;
import com.xs.chat.R;

/**
 * 配对截屏前台服务：MediaProjection 截屏期间保持前台（Android 14+ 要求
 * mediaProjection 类型前台服务配合），防止系统回收截屏能力。
 */
public class PairCaptureService extends Service {
    private static final String TAG = "PairCaptureService";
    private static final String CHANNEL_ID = "pair_capture";
    private static final int NOTIF_ID = 1002;

    private static volatile MediaProjection sMp;
    private static volatile VirtualDisplay sVd;
    private static volatile ImageReader sReader;
    private static volatile int sResultCode;
    private static volatile Intent sData;
    private static volatile boolean sReady;

    public static boolean isReady() { return sReady && sMp != null; }

    public static void setProjection(int resultCode, Intent data, MediaProjection mp) {
        sResultCode = resultCode;
        sData = data;
        sMp = mp;
        sReady = true;
    }

    public static MediaProjection getProjection() { return sMp; }
    public static ImageReader getImageReader() { return sReader; }
    public static int getResultCode() { return sResultCode; }
    public static Intent getData() { return sData; }

    public static void clear() {
        sVd = null;
        sReader = null;
        sMp = null;
        sData = null;
        sResultCode = 0;
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (Throwable t) {
                Log.w(TAG, "startForeground(mediaProjection) failed: " + t.getMessage());
                startForeground(NOTIF_ID, buildNotification());
            }
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        // Android 14+: 必须在获取 MediaProjection 前保证前台服务已运行
        if (intent != null && sMp == null) {
            int rc = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");
            diag("onStart rc=" + rc + " data=" + (data == null ? "null" : "present"));
            if (rc != 0 && data != null) {
                try {
                    MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    if (mpm != null) {
                        MediaProjection mp = mpm.getMediaProjection(rc, data);
                        if (mp != null) {
                            // Android 15+ 强制：createVirtualDisplay 前必须注册 callback 管理资源状态
                            mp.registerCallback(new MediaProjection.Callback() {},
                                new Handler(Looper.getMainLooper()));
                            // Android 16 限制：同一 projection 只能创建一次 VirtualDisplay，
                            // 这里一次性创建并复用，配对轮询只取帧不再重复 create
                            DisplayMetrics dm = getResources().getDisplayMetrics();
                            ImageReader reader = ImageReader.newInstance(
                                dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2);
                            VirtualDisplay vd = mp.createVirtualDisplay(
                                "xs-chat-capture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                reader.getSurface(), null, null);
                            if (vd == null) {
                                reader.close();
                                diag("createVirtualDisplay returned null");
                            } else {
                                sVd = vd;
                                sReader = reader;
                                diag("VirtualDisplay created OK " + dm.widthPixels + "x" + dm.heightPixels);
                            }
                            setProjection(rc, data, mp);
                        } else {
                            diag("getMediaProjection returned null (token consumed/invalid)");
                        }
                    } else {
                        diag("MediaProjectionManager null");
                    }
                } catch (Throwable t) {
                    diag("getMediaProjection exception: " + t.getClass().getName() + " " + t.getMessage());
                    Log.e(TAG, "getMediaProjection in service failed: " + t.getMessage());
                }
            } else {
                diag("onStart missing extras rc=" + rc);
            }
        }
        // 预热 OCR（提取 traineddata + 初始化 Tesseract），使首帧屏幕能即时识别配对码
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                try { ScreenOcr.prepare(); } catch (Throwable t) { Log.w(TAG, "ocr prewarm: " + t.getMessage()); }
            }
        }, "ocr-prewarm");
        th.setDaemon(true);
        th.start();

        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (sVd != null) {
            try { sVd.release(); } catch (Throwable ignored) {}
        }
        if (sReader != null) {
            try { sReader.close(); } catch (Throwable ignored) {}
        }
        if (sMp != null) {
            try { sMp.stop(); } catch (Throwable ignored) {}
        }
        clear();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent(getPackageName()); // 无启动器时兜底
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(R.string.pair_capture_notif_title))
            .setContentText(getString(R.string.pair_capture_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private static void diag(String line) {
        try {
            java.io.File f = new java.io.File(WdbContext.get().getFilesDir(), "pip_diag.txt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fo);
            pw.println(System.currentTimeMillis() + " [PairCaptureService] " + line);
            pw.flush(); pw.close();
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
            getString(R.string.pair_capture_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
