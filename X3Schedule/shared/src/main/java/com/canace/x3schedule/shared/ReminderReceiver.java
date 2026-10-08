package com.canace.x3schedule.shared;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.canace.x3schedule.domain.Execution;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Reminders;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReminderReceiver extends BroadcastReceiver {

    public static synchronized void schedule(Context context, boolean deliver) {
        if (!context.getPackageName().endsWith(".phone")) {
            cancelReminderAlarms(context);
            context.getSystemService(NotificationManager.class).cancel(100);
            // Keep offline recurrence/state maintenance independent of notification delivery.
            try (Store store = new Store(context)) {
                store.rebuild(System.currentTimeMillis());
            }
            return;
        }
        schedulePhone(context, deliver);
    }

    private static void schedulePhone(Context context, boolean deliver) {
        ReminderTrace.event("SCAN", deliver);
        try (Store store = new Store(context)) {
            long now = System.currentTimeMillis();
            store.rebuild(now);
            List<Event> events = store.events();
            Settings settings = store.settings();
            NextReminder next = new NextReminder();
            Set<String> dueInstances = new HashSet<>();
            List<String> keys = new ArrayList<>();
            for (Instance i : store.instances()) {
                for (Reminders.Due due : Reminders.candidates(i, Execution.winner(events, i.id),
                        store.phone, settings)) {
                    if (!store.meta("notified:" + due.key, "").isEmpty()) {
                        continue;
                    }
                    long deliveryAt = Reminders.deliveryAt(due, now, i.zone, settings);
                    if (deliveryAt <= now) {
                        dueInstances.add(i.id);
                        keys.add(due.key);
                    } else {
                        next.offer(due, deliveryAt, now);
                    }
                }
            }
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("schedule", "日程提醒", NotificationManager
                    .IMPORTANCE_HIGH));
            boolean enabled = nm.areNotificationsEnabled() && nm.getNotificationChannel("schedule")
                    .getImportance() != NotificationManager.IMPORTANCE_NONE;
            if (deliver && !dueInstances.isEmpty() && enabled) {
                Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                PendingIntent pi = PendingIntent.getActivity(context, 10, open, PendingIntent
                        .FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(context, "schedule").setSmallIcon(android.R.drawable
                        .ic_menu_my_calendar).setContentTitle("日程提醒").setContentText(dueInstances.size()
                        + " 项日程需要查看或确认").setContentIntent(pi).setAutoCancel(true).setVisibility(Notification
                        .VISIBILITY_PRIVATE).build();
                nm.notify(100, n);
                ReminderTrace.event("NOTIFY", dueInstances.size());
                for (String key : keys) {
                    store.putMeta("notified:" + key, "1");
                }
            }
            if (!dueInstances.isEmpty() && !deliver) {
                next.catchUp(now);
            }
            scheduleAlarm(context, next, enabled);
        }
    }

    private static void scheduleAlarm(Context context, NextReminder next, boolean enabled) {
        cancelReminderAlarms(context);
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 1, new Intent(context, ReminderReceiver.class)
                .setAction("com.canace.x3schedule.TICK"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent
                .FLAG_IMMUTABLE);
        PendingIntent clock = PendingIntent.getBroadcast(context, 2, new Intent(context, ReminderReceiver.class)
                .setAction("com.canace.x3schedule.TICK"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent
                .FLAG_IMMUTABLE);
        ReminderTrace.event("CANCEL", 1);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (clockAlarm(context, am, clock, next, enabled && exact) && next.at == next.clockAt) {
            return;
        }
        if (next.at != Long.MAX_VALUE && enabled) {
            // Automatic retries/catch-up, and user reminders without exact permission, may be delayed.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi);
            ReminderTrace.event("REQUEST_INEXACT", next.at);
        }
    }

    private static void cancelReminderAlarms(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        // Both identities were used by shipped builds; cancel them even with restored legacy settings.
        for (int request : new int[] { 1, 2 }) {
            PendingIntent operation = PendingIntent.getBroadcast(context, request,
                    new Intent(context, ReminderReceiver.class).setAction("com.canace.x3schedule.TICK"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarms.cancel(operation);
        }
    }

    private static boolean clockAlarm(Context context, AlarmManager am, PendingIntent operation, NextReminder next,
            boolean enabled) {
        if (!enabled || next.clockAt == Long.MAX_VALUE) {
            return false;
        }
        Intent open = new Intent(context, ScheduleActivity.class)
                .setData(android.net.Uri.parse("x3schedule://reminder/" + android.net.Uri.encode(next.clockInstanceId)))
                .putExtra("schedule.reminderInstance", next.clockInstanceId);
        PendingIntent show = PendingIntent.getActivity(context, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(next.clockAt, show), operation);
        ReminderTrace.event("REQUEST_CLOCK", next.clockAt);
        ReminderTrace.alarmState(context, am, true);
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null || !java.util.Arrays.asList("com.canace.x3schedule.TICK", Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).contains(action)) {
            return;
        }
        Context application = context.getApplicationContext();
        ReminderTrace.event("RECEIVE", 0);
        PendingResult pending = goAsync();
        ReminderWork.request(application, true, success -> {
            try {
                if (DataMaintenance.active()) {
                    return;
                }
                RefreshJob.register(application);
                if (!application.getPackageName().endsWith(".phone")) {
                    WatchConnectionService.startIfConfigured(application);
                } else if (Build.VERSION.SDK_INT >= 31) {
                    try (Store s = new Store(application)) {
                        PresenceBinding.observe(application, s.meta("peer", ""));
                    }
                }
            } catch (RuntimeException ignored) {
                /* Persisted state is retried on next app start/job. */
            } finally {
                pending.finish();
            }
        });
    }
}
