package com.wirelessdebug.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.xs.chat.R;

/**
 * 前台服务：AI 开始处理消息/操作手机时启动，防止 App 退到后台被系统回收；
 * 结束后停止。通知常驻一条低优先级"运行中"提示。
 */
public class ControlForegroundService extends Service {
    private static final String TAG = "ControlFgService";
    private static final String CHANNEL_ID = "codex_agent_keepalive";
    private static final int NOTIF_ID = 1001;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, ControlForegroundService.class)
                .setAction("start");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
            Log.d(TAG, "fg service start requested");
        } catch (Exception e) {
            Log.w(TAG, "start fg service failed", e);
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, ControlForegroundService.class));
            Log.d(TAG, "fg service stop requested");
        } catch (Exception ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent(getPackageName()); // 无启动器时兜底
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
            getString(R.string.fgs_channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
