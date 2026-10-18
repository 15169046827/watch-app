package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Progress;
import java.time.LocalDate;

/** Explicit UI fixtures in a fresh, unpaired validation UID only; never clears existing data. */
final class ProgressDeviceFixture {
    void run(Instrumentation runner) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            Context context = runner.getTargetContext();
            require(context.getPackageName().equals("com.canace.x3schedule.phone.validation.phone")
                    || context.getPackageName().equals("com.canace.x3schedule.validation"),
                    "Progress fixture requires the isolated validation application");
            seed(context);
            result.putString("stream", "Progress UI fixture ready: 9 plans, 9 events, yesterday; reminders off");
        } catch (RuntimeException error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Progress fixture refused: " + error.getClass().getSimpleName());
        }
        runner.finish(code, result);
    }

    private void seed(Context context) {
        try (Store store = new Store(context, "schedule.db", true)) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                require(store.plans().isEmpty() && store.events().isEmpty(), "Fixture database is not empty");
                require(store.meta("peer", "").isEmpty(), "Validation must not have a sync peer");
                String[] titles = { "未进行", "最低行动", "基本完成", "完整完成", "请假豁免", "未反馈",
                    "原时段未做后自行完整补做", "二分完成", "不定时最低行动" };
                for (int n = 0; n < titles.length; n++) {
                    Plan plan = plan(n, titles[n]);
                    store.save(plan);
                    Instance item = store.instances().stream().filter(i -> i.planId.equals(plan.id)).findFirst().get();
                    feedback(store, item, n);
                }
                require(store.plans().size() == 9 && store.events().size() == 9, "Fixture is incomplete");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    private Plan plan(int index, String title) {
        Plan plan = new Plan();
        plan.id = "progress-fixture-" + index;
        plan.title = title;
        plan.category = "等级验收";
        plan.anchor = LocalDate.now().minusDays(1).toString();
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.startMinute = 540 + index * 60;
        plan.durationMinutes = 45;
        plan.inheritReminders = false;
        plan.reminderTargets = 0;
        plan.progress = new Progress.Options();
        if (index != 7) {
            plan.progress.perfect = "完成五章";
            plan.progress.basic = "完成两章";
            plan.progress.minimum = "打开书并阅读";
        }
        plan.progress.untimed = index == 8;
        return plan;
    }

    private void feedback(Store store, Instance item, int index) {
        if (index == 5) {
            return;
        }
        Progress.Entry entry = new Progress.Entry();
        entry.op = index == 4 ? Progress.Op.LEAVE : Progress.Op.RESULT;
        entry.grade = index == 4 ? null : index < 4 ? Progress.Grade.values()[index]
                : index == 8 ? Progress.Grade.MINIMUM : index == 7 ? Progress.Grade.PERFECT : Progress.Grade.NONE;
        entry.acted = entry.grade != Progress.Grade.NONE;
        store.recordProgress(item, entry);
        if (index == 6) {
            entry.session = Progress.Session.MANUAL;
            entry.grade = Progress.Grade.PERFECT;
            entry.acted = true;
            store.recordProgress(item, entry);
        }
    }
}
