package com.canace.x3schedule.shared;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Receives only validation alarms; timestamp/type traces contain no private data or device IDs. */
public final class AlarmProbeReceiver extends BroadcastReceiver {
    static boolean allowed(Context context) {
        String name = context.getPackageName();
        return name.equals("com.canace.x3schedule.validation")
                || name.equals("com.canace.x3schedule.phone.validation.phone");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int type = intent.getIntExtra("type", -1);
        long target = intent.getLongExtra("target", 0);
        if (!allowed(context) || (type != 0 && type != 2) || target <= 0
                || !("com.canace.x3schedule.PROBE_" + type).equals(intent.getAction())) {
            return;
        }
        Log.i("ScheduleAlarmProbe", "RECEIVE type=" + type + " target=" + target
                + " wall=" + System.currentTimeMillis());
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("alarm_probe", "验收闹钟",
                NotificationManager.IMPORTANCE_HIGH));
        if (manager.areNotificationsEnabled()) {
            Notification notification = new Notification.Builder(context, "alarm_probe")
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle("验收闹钟")
                    .setContentText("独立唤醒闹钟对照").setVisibility(Notification.VISIBILITY_PRIVATE).build();
            manager.notify(710 + type, notification);
            Log.i("ScheduleAlarmProbe", "NOTIFY type=" + type + " target=" + target
                    + " wall=" + System.currentTimeMillis());
        }
    }
}
