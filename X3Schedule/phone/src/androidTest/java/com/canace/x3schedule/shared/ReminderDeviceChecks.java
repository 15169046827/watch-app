package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Progress;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Explicit reminder regression in a synthetic unpaired validation phone; never touches formal app settings. */
final class ReminderDeviceChecks {
    private static final String TITLE = "QA07-Reminder-0.7";

    void run(Instrumentation runner, String mode) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(runner.getTargetContext().getPackageName()
                    .equals("com.canace.x3schedule.phone.validation.phone"), "Validation phone only");
            if (mode.equals("seed") || mode.equals("seed2")) {
                result.putString("stream", seed(runner, mode.equals("seed2") ? TITLE + "-2" : TITLE));
            } else {
                require(mode.equals("inspect"), "Unsupported reminder mode");
                result.putString("stream", inspect(runner));
            }
        } catch (RuntimeException error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Reminder fixture refused: " + error.getMessage());
        }
        runner.finish(code, result);
    }

    private String seed(Instrumentation runner, String title) {
        ZonedDateTime start = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(3)
                .withSecond(0).withNano(0);
        try (Store store = new Store(runner.getTargetContext())) {
            require(store.meta("peer", "").isEmpty(), "Unpaired validation required");
            require(store.plans().stream().noneMatch(p -> p.title.equals(title)), "Reminder fixture exists");
            Settings settings = store.settings();
            settings.quietStart = 0;
            settings.quietEnd = 0;
            settings.phoneRemindersEnabled = true;
            store.settings(settings);
            Plan plan = new Plan();
            plan.title = title;
            plan.anchor = start.toLocalDate().toString();
            plan.rule = Rule.DAYS;
            plan.count = 1;
            plan.startMinute = start.getHour() * 60 + start.getMinute();
            plan.durationMinutes = 1;
            plan.inheritReminders = false;
            plan.reminderTargets = 1;
            plan.progress = new Progress.Options();
            store.save(plan);
        }
        ReminderReceiver.schedule(runner.getTargetContext(), false);
        return title + " ready; validation quiet hours disabled only; start=" + start.toInstant().toEpochMilli()
                + "; end=" + start.plusMinutes(1).toInstant().toEpochMilli();
    }

    private String inspect(Instrumentation runner) {
        NotificationManager manager = runner.getTargetContext().getSystemService(NotificationManager.class);
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            Notification notification = active.getNotification();
            String title = String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE));
            if (title.equals(TITLE) || title.equals(TITLE + "-2")) {
                require(notification.contentIntent != null && (Build.VERSION.SDK_INT < 31
                        || notification.contentIntent.isImmutable()),
                        "Missing immutable content route");
                return "QA07 notification active; postTime=" + active.getPostTime() + "; title=" + title
                        + "; text=" + notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            }
        }
        throw new IllegalArgumentException("QA07 notification not active");
    }
}
