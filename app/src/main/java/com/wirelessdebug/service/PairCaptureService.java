package com.wirelessdebug.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

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
    public static int getResultCode() { return sResultCode; }
    public static Intent getData() { return sData; }

    public static void clear() {
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
            if (rc != 0 && data != null) {
                try {
                    MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    if (mpm != null) {
                        setProjection(rc, data, mpm.getMediaProjection(rc, data));
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "getMediaProjection in service failed: " + t.getMessage());
                }
            }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
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

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
            getString(R.string.pair_capture_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
