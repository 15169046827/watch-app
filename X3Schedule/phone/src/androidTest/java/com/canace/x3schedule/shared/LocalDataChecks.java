package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.util.UUID;

/** Destructive maintenance checks run only in a new synthetic database, never schedule.db. */
final class LocalDataChecks {
    static int run(Context context) {
        String name = "schedule-test-maintenance-" + UUID.randomUUID() + ".db";
        try (Store store = new Store(context, name, true)) {
            Plan plan = new Plan();
            plan.title = "清除回归合成数据";
            plan.anchor = LocalDate.now().toString();
            plan.rule = Rule.DAYS;
            plan.count = 2;
            store.save(plan);
            store.record(store.instances().get(0), Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
            store.putMeta("peer", "synthetic-peer");
            store.putMeta("privacy_notice", "1");
            store.putMeta("notified:synthetic", "1");
            Wire.Snapshot backup = store.snapshot(true);
            String plans = Wire.JSON.toJson(store.plans());
            String events = Wire.JSON.toJson(store.events());
            String settings = Wire.JSON.toJson(store.settings());
            String identity = store.deviceId();
            store.outgoingBatch();
            require(count(store, "batch_out_parts") > 0, "Missing transfer fixture");
            LocalDataStore.clearTransferCache(store);
            require(count(store, "batch_out_parts") == 0, "Transfer cache retained");
            require(plans.equals(Wire.JSON.toJson(store.plans()))
                    && events.equals(Wire.JSON.toJson(store.events())), "Cache clear altered business data");
            require(store.meta("peer", "").equals("synthetic-peer"), "Cache clear removed peer");
            verifyRollback(store, plans, events);
            LocalDataStore.clearBusiness(store);
            require(store.plans().isEmpty() && store.events().isEmpty() && store.instances().isEmpty(),
                    "Business data retained");
            require(store.pending() == 0 && store.meta("sync_paused", "").equals("true"), "Clear queued peer deletion");
            require(settings.equals(Wire.JSON.toJson(store.settings())) && identity.equals(store.deviceId()),
                    "Clear altered retained settings or identity");
            require(store.meta("peer", "").equals("synthetic-peer") && store.meta("privacy_notice", "").equals("1"),
                    "Clear altered connection or notice");
            require(store.meta("notified:synthetic", "").isEmpty(), "Notification metadata retained");
            store.restore(backup);
            store.restore(backup);
            require(plans.equals(Wire.JSON.toJson(store.plans()))
                    && events.equals(Wire.JSON.toJson(store.events())), "Restore changed or duplicated history");
            require(store.meta("sync_paused", "").equals("true"), "Restore resumed sync without choice");
            LocalDataStore.clearBusiness(store);
            LocalDataStore.clearBusiness(store);
            require(store.plans().isEmpty() && store.events().isEmpty(), "Repeated clear failed");
            return 13;
        } finally {
            context.deleteDatabase(name);
        }
    }

    private static long count(Store store, String table) {
        return DatabaseUtils.queryNumEntries(store.getReadableDatabase(), table);
    }

    private static void verifyRollback(Store store, String plans, String events) {
        store.getWritableDatabase().execSQL("CREATE TRIGGER maintenance_failure BEFORE DELETE ON events "
                + "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        try {
            LocalDataStore.clearBusiness(store);
            throw new AssertionError("Expected maintenance transaction failure");
        } catch (SQLiteException expected) {
            require(plans.equals(Wire.JSON.toJson(store.plans()))
                    && events.equals(Wire.JSON.toJson(store.events())), "Partial deletion survived rollback");
        } finally {
            store.getWritableDatabase().execSQL("DROP TRIGGER maintenance_failure");
        }
    }
}
