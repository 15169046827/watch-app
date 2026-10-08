package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import com.canace.x3schedule.domain.Backup;
import com.canace.x3schedule.domain.Batch;
import com.canace.x3schedule.domain.Execution;
import com.canace.x3schedule.domain.Kinds;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Optimizer;
import com.canace.x3schedule.domain.PlanEdits;
import com.canace.x3schedule.domain.Recurrence;
import com.canace.x3schedule.domain.Wire;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All plan/event mutations and their pending revision are committed in the same SQLite transaction.
 */
public final class Store extends SQLiteOpenHelper {

    private final Context context;

    public final boolean phone;

    public Store(Context c) {
        this(c, "schedule.db", c.getPackageName().endsWith(".phone"));
    }

    Store(Context c, String databaseName, boolean phoneRole) {
        super(c, checkedDatabaseName(databaseName), null, 5);
        context = c.getApplicationContext();
        phone = phoneRole;
        setWriteAheadLoggingEnabled(true);
    }

    private static String checkedDatabaseName(String name) {
        require(name.equals("schedule.db") || name.matches("schedule-test-[a-zA-Z0-9-]+\\.db"),
                "Invalid database name");
        return name;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        script(db, "schema-v1.sql");
        script(db, "migration-1-2.sql");
        script(db, "migration-2-3.sql");
        if (db.getVersion() <= 3) {
            script(db, "migration-3-4.sql");
        }
        script(db, "migration-4-5.sql");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 1 || from > 4 || to != 5) {
            throw new SQLiteException("Unsupported migration");
        }
        if (from == 1) {
            script(db, "migration-1-2.sql");
        }
        if (from <= 2) {
            script(db, "migration-2-3.sql");
        }
        if (db.getVersion() <= 3) {
            script(db, "migration-3-4.sql");
        }
        script(db, "migration-4-5.sql");
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int from, int to) {
        throw new SQLiteException("Downgrade is not supported");
    }

    private void script(SQLiteDatabase db, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] part = new byte[4096];
            int n;
            while ((n = in.read(part)) != -1) {
                b.write(part, 0, n);
            }
            for (String sql : b.toString("UTF-8").split(";")) {
                if (!sql.trim().isEmpty()) {
                    db.execSQL(sql);
                }
            }
        } catch (IOException e) {
            throw new SQLiteException("Schema is unavailable");
        }
    }

    public synchronized String meta(String key, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM meta WHERE key=?", new String[] { key })) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }

    public synchronized void putMeta(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String deviceId() {
        String id = meta("device_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            putMeta("device_id", id);
        }
        return id;
    }

    private long number(String k) {
        return Long.parseLong(meta(k, "0"));
    }

    private void dirty() {
        putMeta("revision", Long.toString(number("revision") + 1));
    }

    public synchronized long pending() {
        return Math.max(0, number("revision") - number("acked_revision"));
    }

    private <T> List<T> rows(String table, Class<T> type) {
        List<T> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM " + table + " ORDER BY id", null)) {
            while (c.moveToNext()) {
                out.add(Wire.JSON.fromJson(c.getString(0), type));
            }
        }
        return out;
    }

    public synchronized List<Plan> plans() {
        return rows("plans", Plan.class);
    }

    public synchronized List<Instance> instances() {
        return rows("instances", Instance.class);
    }

    public synchronized List<Event> events() {
        return rows("events", Event.class);
    }

    public synchronized Settings settings() {
        return Wire.JSON.fromJson(meta("settings", Wire.JSON.toJson(new Settings())), Settings.class);
    }

    public synchronized void settings(Settings s) {
        s.validate();
        require(phone, "仅手机修改设置");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            putMeta("settings", Wire.JSON.toJson(s));
            putMeta("settings_version", Long.toString(number("settings_version") + 1));
            rebuild(System.currentTimeMillis());
            dirty();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void putPlan(Plan p) {
        ContentValues v = new ContentValues();
        v.put("id", p.id);
        v.put("version", p.version);
        v.put("body", Wire.JSON.toJson(p));
        getWritableDatabase().insertWithOnConflict("plans", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void putInstance(Instance i) {
        ContentValues v = new ContentValues();
        v.put("id", i.id);
        v.put("plan_id", i.planId);
        v.put("start_ms", i.start);
        v.put("body", Wire.JSON.toJson(i));
        getWritableDatabase().insertWithOnConflict("instances", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void putEvent(Event e) {
        ContentValues v = new ContentValues();
        v.put("id", e.id);
        v.put("instance_id", e.instanceId);
        v.put("logical", e.logical);
        v.put("body", Wire.JSON.toJson(e));
        getWritableDatabase().insertOrThrow("events", null, v);
        putMeta("clock", Long.toString(Math.max(number("clock"), e.logical)));
    }

    public synchronized void save(Plan draft) {
        require(phone, "周期日程只能在手机编辑");
        draft.validate();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Plan prior = plans().stream().filter(p -> p.id.equals(draft.id)).findFirst().orElse(null);
            com.canace.x3schedule.domain.ReminderScope.requireSave(draft, prior, settings());
            if (prior != null) {
                Kinds.requireChange(prior, draft);
                require(draft.kind != Kind.TODO
                        || draft.version == prior.version, "待办已改变，请重新打开");
            }
            draft.version = prior == null ? 1 : prior.version + 1;
            putPlan(draft);
            rebuild(System.currentTimeMillis());
            dirty();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void saveAll(List<Plan> drafts) {
        require(phone, "仅手机编辑");
        for (Plan p : drafts) {
            p.validate();
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Plan p : drafts) {
                save(p);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Acceptance, plan mutation and pending revision are one transaction. */
    public synchronized void acceptAdvice(Optimizer.Advice advice, long now) {
        require(phone, "仅手机应用建议");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Plan current = plans().stream().filter(p -> p.id.equals(advice.suggestion.planId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("计划不存在"));
            Plan draft = Optimizer.preview(current, advice, now);
            save(draft);
            Optimizer.Advice accepted = Wire.copy(advice, Optimizer.Advice.class);
            accepted.suggestion.accepted = true;
            ContentValues values = new ContentValues();
            values.put("id", UUID.randomUUID().toString());
            values.put("plan_id", current.id);
            values.put("body", Wire.JSON.toJson(accepted));
            values.put("accepted", 1);
            db.insertOrThrow("suggestions", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void record(Instance i, Action action, int minutes, boolean earlier, Long actual,
            boolean correction) {
        require(!i.cancelled, "已取消日程不可打卡");
        Kinds.validateAction(i, action);
        require(!correction || phone, "人工修正仅限手机");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Event e = new Event();
            e.deviceId = deviceId();
            e.instanceId = i.id;
            e.snapshot = recordSnapshot(i);
            e.logical = number("clock") + 1;
            e.confirmedAt = System.currentTimeMillis();
            e.action = action;
            e.continueMinutes = minutes;
            e.completedEarlier = earlier;
            e.actualAt = earlier ? null : actual;
            e.correction = correction;
            e.validate();
            putEvent(e);
            dirty();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private Instance recordSnapshot(Instance displayed) {
        if (displayed.kind != Kind.TODO) {
            return Wire.copy(displayed, Instance.class);
        }
        Instance current = instances().stream().filter(i -> i.id.equals(displayed.id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("待办已不存在"));
        require(!current.cancelled, "待办已移除，请刷新");
        return Wire.copy(current, Instance.class);
    }

    private void validateEventPurposes(List<Event> incoming) {
        Map<String, Instance> current = new HashMap<>();
        for (Instance instance : instances()) {
            current.put(instance.id, instance);
        }
        for (Event event : incoming) {
            Instance instance = current.get(event.instanceId);
            if (instance != null) {
                Kinds.validateEventPurpose(instance, event);
            }
        }
    }

    public synchronized void rebuild(long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<Instance> old = instances();
            List<Instance> fresh = new ArrayList<>();
            Set<String> recorded = new HashSet<>();
            for (Event e : events()) {
                recorded.add(e.instanceId);
            }
            for (Plan p : plans()) {
                LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of(p.zone)).toLocalDate();
                fresh.addAll(Recurrence.generate(PlanEdits.withDefaults(p, settings()), today.minusDays(Math.max(32,
                        com.canace.x3schedule.domain.CalendarEditing.lookback(p))), today
                        .plusDays(180)));
            }
            // Keep historical tombstones and snapshots; only regenerate the local rolling future horizon.
            List<Instance> merged = Recurrence.reconcile(old, fresh, recorded, now);
            boolean changed = false;
            Map<String, Instance> prior = new HashMap<>();
            for (Instance i : old) {
                prior.put(i.id, i);
            }
            for (Instance i : merged) {
                if (!Wire.JSON.toJson(i).equals(Wire.JSON.toJson(prior.get(i.id)))) {
                    putInstance(i);
                    changed = true;
                }
            }
            if (changed && phone) {
                dirty();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized Wire.Snapshot snapshot(boolean backup) {
        Wire.Snapshot s = new Wire.Snapshot();
        s.source = phone ? "phone" : "watch";
        s.deviceId = deviceId();
        if (phone || backup) {
            s.plans = plans();
            s.instances = instances();
        }
        s.events = events();
        if (!phone) {
            s.events.removeIf(e -> !e.deviceId.equals(s.deviceId));
        }
        s.settings = settings();
        s.settingsVersion = number("settings_version");
        s.revision = number("revision");
        return s;
    }

    public synchronized byte[] outgoing() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT body FROM outbox LIMIT 1", null)) {
                if (c.moveToFirst()) {
                    byte[] b = c.getBlob(0);
                    db.setTransactionSuccessful();
                    return b;
                }
            }
            byte[] body = Wire.encode(snapshot(false));
            Wire.Snapshot s = Wire.decode(body);
            ContentValues v = new ContentValues();
            v.put("id", s.id);
            v.put("revision", number("revision"));
            v.put("body", body);
            db.insertOrThrow("outbox", null, v);
            db.setTransactionSuccessful();
            return body;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void acknowledge(Wire.Ack a) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT revision,body FROM outbox WHERE id=?", new String[] { a.messageId })) {
                if (c.moveToFirst()) {
                    byte[] body = c.getBlob(1);
                    require(Wire.accepts(a, Wire.decode(body), body), "ACK 校验失败");
                    putMeta("acked_revision", Long.toString(Math.max(number("acked_revision"), c.getLong(0))));
                    db.delete("outbox", "id=?", new String[] { a.messageId });
                    putMeta("last_sync", Long.toString(System.currentTimeMillis()));
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized Wire.Ack receive(byte[] body) {
        Wire.Snapshot s = Wire.decode(body);
        require(phone ? s.source.equals("watch") : s.source.equals("phone"), "同步方向无效");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String digest = Wire.digest(body);
            try (Cursor c = db.rawQuery("SELECT digest FROM inbox WHERE id=?", new String[] { s.id })) {
                if (c.moveToFirst()) {
                    require(digest.equals(c.getString(0)), "重试内容不一致");
                    db.setTransactionSuccessful();
                    return Wire.ack(s, body);
                }
            }
            applyBatch(s);
            ContentValues v = new ContentValues();
            v.put("id", s.id);
            v.put("digest", digest);
            db.insertOrThrow("inbox", null, v);
            db.setTransactionSuccessful();
            return Wire.ack(s, body);
        } finally {
            db.endTransaction();
        }
    }

    public synchronized byte[] outgoingBatch() {
        return new BatchStore(this).outgoing();
    }

    public synchronized boolean acknowledgeBatch(Batch.Ack ack) {
        return new BatchStore(this).acknowledge(ack);
    }

    public synchronized Batch.Ack receiveBatch(byte[] packet) {
        return new BatchStore(this).receive(packet);
    }

    void applyBatch(Wire.Snapshot s) {
        require(getWritableDatabase().inTransaction(), "批次必须事务提交");
        require(phone ? s.source.equals("watch") : s.source.equals("phone"), "同步方向无效");
        if (!phone && s.revision >= number("remote_revision:" + s.deviceId)) {
            receiveDefinitions(s);
        }
        if (!phone) {
            putMeta("remote_revision:" + s.deviceId, Long.toString(Math.max(number("remote_revision:" + s
                    .deviceId), s.revision)));
        }
        receiveEvents(s.events);
    }

    public synchronized void restore(byte[] bytes) {
        restore(Wire.decodeBackup(bytes));
    }

    public synchronized void restore(Wire.Snapshot s) {
        require(phone, "恢复只在手机进行");
        Backup.validate(s);
        require(s.source.equals("phone"), "需手机备份");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            restorePlans(s);
            restoreEvents(s);
            restoreInstances(s);
            if (s.settingsVersion > number("settings_version")) {
                putMeta("settings", Wire.JSON.toJson(s.settings));
                putMeta("settings_version", Long.toString(s.settingsVersion));
            }
            rebuild(System.currentTimeMillis());
            dirty();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void receiveDefinitions(Wire.Snapshot s) {
        Map<String, Plan> old = new HashMap<>();
        for (Plan p : plans()) {
            old.put(p.id, p);
        }
        for (Plan p : s.plans) {
            putPlan(Wire.mergePlan(old.get(p.id), p, s.source));
        }
        Map<String, Instance> existing = new HashMap<>();
        for (Instance i : instances()) {
            existing.put(i.id, i);
        }
        for (Instance i : s.instances) {
            Instance prior = existing.get(i.id);
            if (prior == null || i.planVersion >= prior.planVersion) {
                putInstance(i);
            }
        }
        if (s.settingsVersion >= number("settings_version")) {
            putMeta("settings", Wire.JSON.toJson(s.settings));
            putMeta("settings_version", Long.toString(s.settingsVersion));
        }
    }

    private void receiveEvents(List<Event> incoming) {
        validateEventPurposes(incoming);
        List<Event> oldEvents = events();
        List<Event> all = Wire.mergeEvents(oldEvents, incoming);
        Set<String> ids = new HashSet<>();
        for (Event e : oldEvents) {
            ids.add(e.id);
        }
        for (Event e : all) {
            if (!ids.contains(e.id)) {
                putEvent(e);
            }
        }
        for (Instance i : instances()) {
            putInstance(Execution.frozen(i, all));
        }
        if (phone && all.size() != oldEvents.size()) {
            dirty();
        }
    }

    private void restorePlans(Wire.Snapshot s) {
        Map<String, Plan> old = new HashMap<>();
        for (Plan p : plans()) {
            old.put(p.id, p);
        }
        for (Plan p : s.plans) {
            Plan current = old.get(p.id);
            if (current == null || p.version > current.version) {
                if (current != null) {
                    Kinds.requireChange(current, p);
                }
                putPlan(p);
            } else if (p.version == current.version) {
                Wire.mergePlan(current, p, "phone");
            }
        }
    }

    private void restoreEvents(Wire.Snapshot s) {
        validateEventPurposes(s.events);
        Set<String> ids = new HashSet<>();
        List<Event> existing = events();
        for (Event e : existing) {
            ids.add(e.id);
        }
        for (Event e : Wire.mergeEvents(existing, s.events)) {
            if (!ids.contains(e.id)) {
                putEvent(e);
            }
        }
    }

    private void restoreInstances(Wire.Snapshot s) {
        Set<String> instanceIds = new HashSet<>();
        for (Instance i : instances()) {
            instanceIds.add(i.id);
        }
        for (Instance i : s.instances) {
            if (!instanceIds.contains(i.id)) {
                putInstance(i);
            }
        }
    }
}
