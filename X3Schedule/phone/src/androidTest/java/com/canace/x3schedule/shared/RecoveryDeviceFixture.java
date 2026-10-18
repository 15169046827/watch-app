package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Progress;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Adds three no-reminder, local-only UI cases without clearing existing synthetic history. */
final class RecoveryDeviceFixture {
    void run(Instrumentation runner) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            Context context = runner.getTargetContext();
            require(context.getPackageName().equals("com.canace.x3schedule.phone.validation.phone")
                    || context.getPackageName().equals("com.canace.x3schedule.validation"), "Validation only");
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            require(now.getHour() >= 5 && now.getHour() < 23, "Run between 05:00 and 23:00");
            try (Store store = new Store(context, "schedule.db", true)) {
                require(store.meta("peer", "").isEmpty(), "Unpaired validation required");
                require(store.plans().stream().noneMatch(p -> p.title.startsWith("QA07-LIVE-")),
                        "Live fixture already exists");
                store.getWritableDatabase().beginTransaction();
                try {
                    store.save(task(now, "QA07-LIVE-Recovery", 4));
                    store.save(task(now, "QA07-LIVE-Conflict", 10));
                    Plan occupied = plan(now.plusMinutes(10), "QA07-LIVE-Occupied", 3);
                    store.save(occupied);
                    store.getWritableDatabase().setTransactionSuccessful();
                } finally {
                    store.getWritableDatabase().endTransaction();
                }
            }
            result.putString("stream", "Live UI cases added:3; reminders off; recovery=" + now.plusMinutes(4)
                    + "; hardConflict=" + now.plusMinutes(10));
        } catch (RuntimeException error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Live fixture refused: " + error.getClass().getSimpleName());
        }
        runner.finish(code, result);
    }

    private Plan task(ZonedDateTime now, String title, int afterMinutes) {
        Plan plan = plan(now.minusMinutes(4), title, 1);
        plan.progress = new Progress.Options();
        plan.progress.perfect = "QA07-all";
        plan.progress.basic = "QA07-most";
        plan.progress.minimum = "QA07-started";
        ZonedDateTime recovery = now.plusMinutes(afterMinutes);
        plan.progress.recoveryMinute = recovery.getHour() * 60 + recovery.getMinute();
        plan.progress.recoveryDuration = 1;
        return plan;
    }

    private Plan plan(ZonedDateTime start, String title, int duration) {
        Plan plan = new Plan();
        plan.title = title;
        plan.anchor = start.toLocalDate().toString();
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.startMinute = start.getHour() * 60 + start.getMinute();
        plan.durationMinutes = duration;
        plan.inheritReminders = false;
        plan.reminderTargets = 0;
        return plan;
    }
}
