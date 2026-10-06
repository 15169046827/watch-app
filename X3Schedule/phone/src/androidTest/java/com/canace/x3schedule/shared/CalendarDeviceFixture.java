package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Explicitly seeded, persistent synthetic UI data, allowed only in the separate validation APK. */
final class CalendarDeviceFixture {
    void run(Instrumentation runner) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            Context context = runner.getTargetContext();
            require(context.getPackageName().equals("com.canace.x3schedule.phone.validation.phone")
                    || context.getPackageName().equals("com.canace.x3schedule.validation"),
                    "Calendar fixture requires the isolated validation application");
            seed(context);
            result.putString("stream", "Calendar UI fixture ready: 43 plans, 3 historical events; reminders off");
        } catch (RuntimeException error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Calendar fixture refused: " + error.getClass().getSimpleName());
        }
        runner.finish(code, result);
    }

    private void seed(Context context) {
        // No deletion or reset path: an existing fixture must be inspected and deliberately reused.
        try (Store store = new Store(context, "schedule.db", true)) {
            require(store.plans().isEmpty() && store.events().isEmpty(), "Fixture database is not empty");
            require(store.meta("peer", "").isEmpty(), "Validation must not have a sync peer");
            List<Plan> plans = new ArrayList<>();
            LocalDate today = LocalDate.now();
            String[] names = { "ScopeOne", "ScopeFollowing", "ScopeSeries" };
            for (int n = 0; n < names.length; n++) {
                Plan plan = plan("scope-" + n, names[n], today.minusDays(1));
                plan.count = 8;
                plan.startMinute = 540 + n * 180;
                plans.add(plan);
            }
            for (int n = 0; n < 40; n++) {
                Plan plan = plan("dense-" + n, "密集日程" + n + "：长标题会议与项目安排可完整查看", today.plusDays(4));
                plan.kind = Kind.REMINDER;
                plan.dateOnly = n >= 32;
                plan.durationMinutes = plan.dateOnly ? 0 : 90;
                plan.daySpan = plan.dateOnly ? 3 : 1;
                plans.add(plan);
            }
            store.saveAll(plans);
            for (Instance item : store.instances()) {
                if (item.planId.startsWith("scope-") && item.date.equals(today.minusDays(1).toString())) {
                    store.record(item, Action.COMPLETE, 0, false, item.end, true);
                }
            }
            require(store.plans().size() == 43 && store.events().size() == 3, "Fixture is incomplete");
        }
    }

    private Plan plan(String id, String title, LocalDate date) {
        Plan plan = new Plan();
        plan.id = id;
        plan.title = title;
        plan.category = "验收数据";
        plan.anchor = date.toString();
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.startMinute = 600;
        plan.reminderTargets = 0;
        plan.inheritReminders = false;
        return plan;
    }
}
