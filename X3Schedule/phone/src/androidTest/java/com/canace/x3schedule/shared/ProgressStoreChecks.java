package com.canace.x3schedule.shared;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.util.UUID;

/** Real SQLite transactions against isolated synthetic databases; no user database is opened. */
final class ProgressStoreChecks {
    private final Context context;
    private int checks;

    ProgressStoreChecks(Context context) {
        this.context = context;
    }

    int run() {
        String phoneName = "schedule-test-progress-phone-" + UUID.randomUUID() + ".db";
        String watchName = "schedule-test-progress-watch-" + UUID.randomUUID() + ".db";
        try (Store phone = new Store(context, phoneName, true);
                Store watch = new Store(context, watchName, false)) {
            Plan plan = new Plan();
            plan.title = "合成等级事务";
            plan.anchor = LocalDate.now().minusDays(1).toString();
            plan.rule = Rule.DAYS;
            plan.count = 1;
            plan.progress = new Progress.Options();
            phone.save(plan);
            Instance item = phone.instances().stream().filter(i -> i.planId.equals(plan.id)).findFirst().get();
            phone.recordProgress(item, result(Progress.Session.ORIGINAL, Progress.Grade.NONE));
            watch.receive(Wire.encode(phone.snapshot(false)));
            check(Progress.summarize(item, watch.events()).originalMiss);
            try {
                watch.recordProgress(item, result(Progress.Session.MANUAL, Progress.Grade.PERFECT));
                throw new AssertionError("Watch accepted phone-only manual recovery");
            } catch (IllegalArgumentException expected) {
                check(watch.events().size() == 1);
            }
            rollback(phone, item);
            phone.recordProgress(item, result(Progress.Session.MANUAL, Progress.Grade.PERFECT));
            Wire.Snapshot backup = phone.snapshot(true);
            phone.restore(backup);
            check(phone.events().size() == 2);
            watch.receive(Wire.encode(phone.snapshot(false)));
            watch.receive(Wire.encode(phone.snapshot(false)));
            Progress.Summary summary = Progress.summarize(item, watch.events());
            check(summary.grade == Progress.Grade.PERFECT);
            check(Progress.score(summary.grade, summary.deviations()) == 80);
            check(summary.manualActions == 1);
            return checks;
        } finally {
            context.deleteDatabase(phoneName);
            context.deleteDatabase(watchName);
        }
    }

    private void rollback(Store store, Instance item) {
        long pending = store.pending();
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_progress BEFORE INSERT ON events BEGIN "
                + "SELECT RAISE(ABORT, 'synthetic progress rollback'); END");
        try {
            store.recordProgress(item, result(Progress.Session.MANUAL, Progress.Grade.PERFECT));
            throw new AssertionError("Progress write did not roll back");
        } catch (SQLiteException expected) {
            check(store.events().size() == 1);
            check(store.pending() == pending);
        } finally {
            store.getWritableDatabase().execSQL("DROP TRIGGER reject_progress");
        }
    }

    private static Progress.Entry result(Progress.Session session, Progress.Grade grade) {
        Progress.Entry entry = new Progress.Entry();
        entry.op = Progress.Op.RESULT;
        entry.session = session;
        entry.grade = grade;
        entry.acted = grade != Progress.Grade.NONE;
        return entry;
    }

    private void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Synthetic progress store check " + (checks + 1));
        }
        checks++;
    }
}
