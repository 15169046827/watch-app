package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.RecoveryPlanner;
import java.time.ZonedDateTime;
import java.util.List;

/** Production protocol over real BLE: original result, watch commitment, phone cancellation, acknowledgements. */
final class ProgressBleChecks {
    private ProgressBleChecks() {
    }

    static void seed(Store store) {
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        ZonedDateTime original = now.minusMinutes(10);
        ZonedDateTime recovery = now.plusMinutes(30);
        require(original.toLocalDate().equals(recovery.toLocalDate()), "Run away from midnight");
        Plan plan = new Plan();
        plan.title = "QA07-BLE-Recovery";
        plan.anchor = original.toLocalDate().toString();
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.startMinute = original.getHour() * 60 + original.getMinute();
        plan.durationMinutes = 1;
        plan.inheritReminders = false;
        plan.reminderTargets = 0;
        plan.progress = new Progress.Options();
        plan.progress.perfect = "QA07-all";
        plan.progress.basic = "QA07-most";
        plan.progress.minimum = "QA07-started";
        plan.progress.recoveryMinute = recovery.getHour() * 60 + recovery.getMinute();
        plan.progress.recoveryDuration = 5;
        store.save(plan);
        Progress.Entry entry = new Progress.Entry();
        entry.op = Progress.Op.RESULT;
        entry.grade = Progress.Grade.MINIMUM;
        store.recordProgress(store.instances().get(0), entry);
    }

    static void await(Store store, BleLink link, long started) throws InterruptedException {
        while (System.currentTimeMillis() - started < 240000) {
            List<Instance> items = store.instances();
            if (!items.isEmpty()) {
                require(store.plans().size() == 1 && items.size() == 1, "Unexpected progress fixture");
                Instance item = items.get(0);
                Progress.Summary state = Progress.summarize(item, store.events());
                require(item.progress != null && item.progress.graded(), "Progress definition lost");
                require(state.original == Progress.Grade.MINIMUM && !state.originalMiss,
                        "Original result did not transfer");
                step(store, item, state);
                if (store.events().size() == 3 && !state.enabled && state.enabledCount == 1
                        && !state.recoveryConflict && state.deviations() == 0 && store.pending() == 0) {
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Progress BLE timeout; status=" + link.status);
    }

    private static void step(Store store, Instance item, Progress.Summary state) {
        Progress.Entry entry = new Progress.Entry();
        entry.session = Progress.Session.SCHEDULED;
        entry.recoveryParent = state.recoveryHead;
        if (!store.phone && state.enabledCount == 0) {
            entry.op = Progress.Op.ENABLE;
            long[] window = RecoveryPlanner.standby(item, state);
            entry.start = window[0];
            entry.end = window[1];
            List<RecoveryPlanner.Impact> impacts = RecoveryPlanner.preview(item, entry.start, entry.end,
                    store.instances(), store.events(), System.currentTimeMillis());
            require(impacts.isEmpty(), "Unexpected synthetic conflict");
            entry.conflictKey = RecoveryPlanner.fingerprint(item, entry.start, entry.end, impacts);
            store.recordProgress(item, entry);
        } else if (store.phone && state.enabled) {
            entry.op = Progress.Op.CANCEL;
            store.recordProgress(item, entry);
        }
    }
}
