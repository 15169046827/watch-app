package com.canace.x3schedule.shared;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * A foreground service keeps the non-Wear-OS watch available for its selected phone.
 */
public final class WatchConnectionService extends Service {

    public static void startIfConfigured(Context context) {
        if (context.getPackageName().endsWith(".phone")) {
            return;
        }
        try (Store store = new Store(context)) {
            if (store.meta("peer", "").isEmpty() || !store.meta("background_sync", "true").equals("true")
                    || store.meta("sync_paused", "false").equals("true")) {
                return;
            }
        }
        context.startForegroundService(new Intent(context, WatchConnectionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("connection", "日程后台连接", NotificationManager
                .IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 32, getPackageManager()
                .getLaunchIntentForPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent
                .FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "connection").setSmallIcon(android.R.drawable
                .ic_menu_my_calendar).setContentTitle("日程后台连接").setContentText("可与已配对手机自动补同步")
                .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        startForeground(32, n);
        SyncRuntime.get(this).acquire("watch-service");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SyncRuntime.get(this).release("watch-service");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
