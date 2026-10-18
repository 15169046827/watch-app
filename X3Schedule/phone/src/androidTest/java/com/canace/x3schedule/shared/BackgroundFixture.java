package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.LocalDate;

/** Prepares a separate application for testing real services after instrumentation has exited. */
final class BackgroundFixture {
    void run(Instrumentation runner, String action, String peer) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            Context context = runner.getTargetContext();
            require(context.getPackageName().equals("com.canace.x3schedule.phone.background.phone")
                    || context.getPackageName().equals("com.canace.x3schedule.background"),
                    "Background fixture requires its own application");
            require(context.getString(R.string.schedule_ble_service).endsWith("ad001")
                    && context.getString(R.string.schedule_ble_data).endsWith("ad002"), "Wrong BLE namespace");
            try (Store store = new Store(context)) {
                apply(store, action, peer);
                result.putString("stream", "Background fixture " + action + "; plans=" + store.plans().size()
                        + "; events=" + store.events().size() + "; pending=" + store.pending());
            }
        } catch (RuntimeException error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Background fixture refused: " + error.getMessage());
        }
        runner.finish(code, result);
    }

    private void apply(Store store, String action, String peer) {
        if (action.equals("seed")) {
            seed(store, peer);
            return;
        }
        require(store.meta("background_fixture", "").equals("1"), "No background fixture");
        if (action.equals("complete")) {
            require(!store.phone && store.events().isEmpty(), "Completion requires fresh watch fixture");
            Instance todo = store.instances().stream().filter(item -> item.kind == Kind.TODO)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Sync the initial fixture first"));
            store.record(todo, Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
        } else if (action.equals("add")) {
            require(store.phone && store.plans().size() == 2, "Addition requires initial phone fixture");
            store.save(plan("后台补传新增", Kind.REMINDER));
        } else {
            require(action.equals("inspect"), "Unknown fixture action");
        }
    }

    private void seed(Store store, String peer) {
        require(peer != null && peer.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"), "Invalid peer");
        require(store.plans().isEmpty() && store.events().isEmpty()
                && store.meta("background_fixture", "").isEmpty(), "Refusing to replace existing data");
        SQLiteDatabase database = store.getWritableDatabase();
        database.beginTransaction();
        try {
            if (store.phone) {
                Settings settings = store.settings();
                settings.phoneRemindersEnabled = false;
                settings.watchRemindersEnabled = false;
                store.settings(settings);
                store.save(plan("后台验收待办", Kind.TODO));
                store.save(plan("后台验收提醒", Kind.REMINDER));
            }
            store.putMeta("peer", peer);
            store.putMeta("background_fixture", "1");
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private Plan plan(String title, Kind kind) {
        Plan plan = new Plan();
        plan.title = title;
        plan.kind = kind;
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.anchor = LocalDate.now().toString();
        plan.dateOnly = kind == Kind.REMINDER;
        plan.durationMinutes = 0;
        plan.inheritReminders = false;
        plan.reminderTargets = 0;
        return plan;
    }
}
