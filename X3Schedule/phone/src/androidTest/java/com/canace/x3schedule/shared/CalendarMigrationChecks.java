package com.canace.x3schedule.shared;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.util.UUID;

/** v4 and v5 share table layouts; only unfinished transport data changes during this upgrade. */
final class CalendarMigrationChecks {
    private final Context context;
    private int checks;

    CalendarMigrationChecks(Context context) {
        this.context = context;
    }

    int run() {
        String name = "schedule-test-calendar-upgrade-" + UUID.randomUUID() + ".db";
        try {
            String[] before = seed(name);
            try (Store upgraded = new Store(context, name, true)) {
                SQLiteDatabase db = upgraded.getReadableDatabase();
                check(db.getVersion() == 6);
                check(before[0].equals(Wire.JSON.toJson(upgraded.plans())));
                check(before[1].equals(Wire.JSON.toJson(upgraded.instances())));
                check(before[2].equals(Wire.JSON.toJson(upgraded.events())));
                check(before[3].equals(Wire.JSON.toJson(upgraded.settings())));
                check(upgraded.meta("peer", "").equals("synthetic-peer"));
                check(upgraded.meta("revision", "").equals("19"));
                check(upgraded.meta("acked_revision", "").equals("9"));
                check(DatabaseUtils.stringForQuery(db, "SELECT manifest FROM batch_receipts WHERE id='done'",
                        null).equals("retained-receipt"));
                check(DatabaseUtils.stringForQuery(db, "SELECT hex(body) FROM legacy_outbox_v1 WHERE id='legacy'",
                        null).equals("010203"));
                for (String table : new String[] { "outbox", "batch_out", "batch_out_parts", "batch_in",
                    "batch_in_parts" }) {
                    check(DatabaseUtils.queryNumEntries(db, table) == 0);
                }
                check(upgraded.pending() > 0);
            }
            return checks;
        } finally {
            context.deleteDatabase(name);
        }
    }

    private String[] seed(String name) {
        try (Store store = new Store(context, name, true)) {
            Plan plan = new Plan();
            plan.title = "合成迁移历史";
            plan.rule = Rule.DAYS;
            plan.anchor = LocalDate.now().minusDays(1).toString();
            plan.count = 3;
            store.save(plan);
            store.record(store.instances().get(0), Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
            Settings settings = store.settings();
            settings.reminderMinutes = 12;
            settings.leaveWeek = 2;
            store.settings(settings);
            store.putMeta("peer", "synthetic-peer");
            store.putMeta("revision", "17");
            store.putMeta("acked_revision", "9");
            String[] before = { Wire.JSON.toJson(store.plans()), Wire.JSON.toJson(store.instances()),
                Wire.JSON.toJson(store.events()), Wire.JSON.toJson(store.settings()) };
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("INSERT INTO outbox VALUES ('pending',17,X'01')");
            db.execSQL("INSERT INTO legacy_outbox_v1 VALUES ('legacy',2,X'010203')");
            db.execSQL("INSERT INTO batch_out VALUES (1,'pending-out',0)");
            db.execSQL("INSERT INTO batch_out_parts VALUES (0,X'02')");
            db.execSQL("INSERT INTO batch_in VALUES (1,'pending-in')");
            db.execSQL("INSERT INTO batch_in_parts VALUES (0,X'03','digest')");
            db.execSQL("INSERT INTO batch_receipts VALUES ('done','retained-receipt','retained-digests')");
            db.setVersion(4);
            return before;
        }
    }

    private void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Synthetic calendar migration check " + (checks + 1));
        }
        checks++;
    }
}
