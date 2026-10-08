package com.canace.x3schedule.shared;

import android.content.Context;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.ReminderScope;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.util.UUID;

/** Real SQLite compatibility checks, using disposable databases only; never schedules notifications. */
final class ReminderScopeStoreChecks {
    private int checks;

    static int run(Context context) {
        String suffix = UUID.randomUUID().toString();
        String phoneName = "schedule-test-scope-phone-" + suffix + ".db";
        String watchName = "schedule-test-scope-watch-" + suffix + ".db";
        try (Store phone = new Store(context, phoneName, true);
                Store watch = new Store(context, watchName, false)) {
            ReminderScopeStoreChecks test = new ReminderScopeStoreChecks();
            test.rejected(phone, legacy());
            test.check(phone.plans().isEmpty() && phone.pending() == 0);
            test.restoredAndSynchronized(phone, watch);
            return test.checks;
        } finally {
            context.deleteDatabase(phoneName);
            context.deleteDatabase(watchName);
        }
    }

    private static Plan legacy() {
        Plan plan = new Plan();
        plan.title = "合成旧提醒设置";
        plan.anchor = LocalDate.now().plusDays(2).toString();
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.inheritReminders = false;
        plan.reminderTargets = 2;
        return plan;
    }

    private void restoredAndSynchronized(Store phone, Store watch) {
        Wire.Snapshot backup = new Wire.Snapshot();
        backup.source = "phone";
        backup.deviceId = "synthetic";
        backup.settingsVersion = 1;
        backup.plans.add(legacy());
        backup.settings.reminderTargets = 2;
        backup.settings.phoneRemindersEnabled = false;
        backup.settings.watchRemindersEnabled = true;
        phone.restore(backup);
        watch.receive(Wire.encode(backup));
        for (Store store : new Store[] { phone, watch }) {
            check(store.plans().size() == 1);
            check(ReminderScope.pending(store.plans().get(0), store.settings()));
            check(store.plans().get(0).reminderTargets == 2 && !store.settings().phoneRemindersEnabled);
        }
        Plan inherited = legacy();
        inherited.inheritReminders = true;
        rejected(phone, inherited);
        Plan current = phone.plans().get(0);
        phone.save(current);
        check(phone.plans().get(0).reminderTargets == 2 && !phone.settings().phoneRemindersEnabled);
        for (int target : new int[] { 1, 0 }) {
            current.reminderTargets = target;
            phone.save(current);
            check(!ReminderScope.pending(phone.plans().get(0), phone.settings()));
            check(!phone.settings().phoneRemindersEnabled);
        }
    }

    private void rejected(Store store, Plan draft) {
        long pending = store.pending();
        int size = store.plans().size();
        try {
            store.save(draft);
            throw new AssertionError("New watch-only reminder accepted");
        } catch (IllegalArgumentException expected) {
            check(store.pending() == pending && store.plans().size() == size);
        }
    }

    private void check(boolean value) {
        if (!value) {
            throw new AssertionError("Synthetic reminder scope check " + checks);
        }
        checks++;
    }
}
