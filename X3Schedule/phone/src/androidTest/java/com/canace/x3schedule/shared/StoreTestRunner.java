package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import com.canace.x3schedule.domain.Backup;
import com.canace.x3schedule.domain.Execution;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.State;
import com.canace.x3schedule.domain.Optimizer;
import com.canace.x3schedule.domain.Wire;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Device-only, synthetic databases; never opens or clears the user's schedule.db.
 */
public final class StoreTestRunner extends Instrumentation {

    private int checks;

    private String blePeer;

    private boolean documentResults;

    private String backgroundFixture;

    private String backgroundPeer;

    private String bleRecovery;

    private boolean bleAwakeCpu;

    private boolean bleForeground;

    private boolean uiState;

    private boolean calendarFixture;
    private boolean progressFixture;
    private boolean bleProgress;
    private boolean recoveryFixture;
    private boolean progressLifecycle;
    private boolean progressConflictUi;
    private String reminderFixture;
    private String uiText;
    private String uiField;

    private boolean saveLifecycle;

    private boolean configuration;

    private boolean alarmState;

    private String transactionCrash;

    private String crashId;

    private void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Synthetic store check " + (checks + 1));
        }
        checks++;
    }

    private void adviceTransaction(Store store) {
        Plan original = store.plans().get(0);
        Optimizer.Advice advice = new Optimizer.Advice();
        advice.version = original.version;
        advice.adjustable = true;
        advice.title = "合成建议";
        advice.reason = "事务验证";
        advice.suggestion = Execution.suggest(original.id, 6, 6, "START",
                Integer.toString(original.startMinute), Integer.toString(original.startMinute + 10),
                original.anchor);
        long pending = store.pending();
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_test_advice BEFORE INSERT ON suggestions BEGIN "
                + "SELECT RAISE(ABORT, 'synthetic'); END");
        try {
            store.acceptAdvice(advice, System.currentTimeMillis());
            throw new AssertionError("Injected failure did not abort");
        } catch (SQLiteException expected) {
            check(store.plans().get(0).version == original.version);
            check(store.pending() == pending);
            check(DatabaseUtils.queryNumEntries(db, "suggestions") == 0);
        } finally {
            db.execSQL("DROP TRIGGER reject_test_advice");
        }
        store.acceptAdvice(advice, System.currentTimeMillis());
        check(store.plans().get(0).startMinute == original.startMinute + 10);
        check(store.plans().get(0).version == original.version + 1);
        long acceptedPending = store.pending();
        check(acceptedPending > pending);
        check(DatabaseUtils.queryNumEntries(db, "suggestions", "accepted=1") == 1);
        try {
            store.acceptAdvice(advice, System.currentTimeMillis());
            throw new AssertionError("Stale advice accepted twice");
        } catch (IllegalArgumentException expected) {
            check(store.pending() == acceptedPending);
            check(DatabaseUtils.queryNumEntries(db, "suggestions") == 1);
        }
    }

    private void backupTransaction(Store store) throws IOException {
        Wire.Snapshot before = store.snapshot(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Backup.write(before, bytes);
        store.restore(Backup.read(new ByteArrayInputStream(bytes.toByteArray())));
        check(store.events().size() == before.events.size());
        long pending = store.pending();
        Wire.Snapshot conflict = store.snapshot(true);
        conflict.plans.get(0).version++;
        conflict.plans.get(0).title = "不能部分提交";
        conflict.events.get(0).logical++;
        bytes.reset();
        Backup.write(conflict, bytes);
        try {
            store.restore(Backup.read(new ByteArrayInputStream(bytes.toByteArray())));
            throw new AssertionError("Archive event conflict was accepted");
        } catch (IllegalArgumentException expected) {
            check(store.plans().get(0).title.equals(before.plans.get(0).title));
            check(store.plans().get(0).version == before.plans.get(0).version);
            check(store.pending() == pending);
        }
    }

    private Instance todo(Store store, String planId) {
        return store.instances().stream().filter(i -> i.planId.equals(planId)).findFirst().get();
    }

    private void typeSync(Store phone, Store watch) {
        Plan plan = new Plan();
        plan.kind = Kind.TODO;
        plan.title = "合成跨端待办";
        plan.rule = Rule.DAYS;
        plan.count = 1;
        phone.save(plan);
        watch.receive(Wire.encode(phone.snapshot(false)));
        Instance item = todo(watch, plan.id);
        check(!item.timed && item.start == 0);
        watch.record(item, Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
        phone.receive(Wire.encode(watch.snapshot(false)));
        check(Execution.state(todo(phone, plan.id), Execution.winner(phone.events(), item.id),
                System.currentTimeMillis()) == State.TODO_DONE);
        plan.todoDue = "2027-01-01T13:00";
        phone.save(plan);
        watch.receive(Wire.encode(phone.snapshot(false)));
        item = todo(watch, plan.id);
        check(item.timed);
        check(Execution.state(item, Execution.winner(watch.events(), item.id),
                System.currentTimeMillis()) == State.TODO_DONE);
        watch.record(item, Action.REOPEN, 0, false, System.currentTimeMillis(), false);
        phone.receive(Wire.encode(watch.snapshot(false)));
        check(Execution.winner(phone.events(), item.id).action == Action.REOPEN);
        plan.deleted = true;
        phone.save(plan);
        watch.receive(Wire.encode(phone.snapshot(false)));
        check(todo(watch, plan.id).cancelled);
        check(todo(phone, plan.id).cancelled);
    }

    private void resumeAndTypeSync(String phoneName, String watchName) {
        try (Store phone = new Store(getTargetContext(), phoneName, true);
            Store watch = new Store(getTargetContext(), watchName, false)) {
            check(phone.events().size() == 1);
            check(phone.pending() > 0);
            check(phone.outgoing().length > 0);
            typeSync(phone, watch);
        }
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        documentResults = arguments != null && "true".equals(arguments.getString("documentResults"));
        backgroundFixture = arguments == null ? null : arguments.getString("backgroundFixture");
        backgroundPeer = arguments == null ? null : arguments.getString("backgroundPeer");
        blePeer = arguments == null ? null : arguments.getString("blePeer");
        bleRecovery = arguments == null ? null : arguments.getString("bleRecovery");
        bleForeground = arguments != null && "true".equals(arguments.getString("bleForeground"));
        bleAwakeCpu = arguments != null && "true".equals(arguments.getString("bleAwakeCpu"));
        uiState = arguments != null && "true".equals(arguments.getString("uiState"));
        calendarFixture = arguments != null && "true".equals(arguments.getString("calendarFixture"));
        progressFixture = enabled(arguments, "progressFixture");
        bleProgress = enabled(arguments, "bleProgress");
        recoveryFixture = enabled(arguments, "recoveryFixture");
        progressLifecycle = enabled(arguments, "progressLifecycle");
        progressConflictUi = enabled(arguments, "progressConflictUi");
        reminderFixture = option(arguments, "reminderFixture");
        uiText = option(arguments, "uiText");
        uiField = option(arguments, "uiField");
        saveLifecycle = arguments != null && "true".equals(arguments.getString("saveLifecycle"));
        configuration = arguments != null && "true".equals(arguments.getString("configuration"));
        alarmState = arguments != null && "true".equals(arguments.getString("alarmState"));
        transactionCrash = arguments == null ? null : arguments.getString("transactionCrash");
        crashId = arguments == null ? null : arguments.getString("crashId");
        start();
    }

    private static boolean enabled(Bundle arguments, String option) {
        return arguments != null && "true".equals(arguments.getString(option));
    }

    private static String option(Bundle arguments, String name) {
        return arguments == null ? null : arguments.getString(name);
    }

    @Override
    public void onStart() {
        if (uiText != null) {
            new UiTextChecks().run(this, uiField, uiText);
            return;
        }
        if (alarmState) {
            AlarmCapabilityChecks.run(this);
            return;
        }
        if (documentResults) {
            new DocumentResultChecks().run(this);
            return;
        }
        if (backgroundFixture != null) {
            new BackgroundFixture().run(this, backgroundFixture, backgroundPeer);
            return;
        }
        if (transactionCrash != null) {
            new TransactionCrashChecks(this, transactionCrash, crashId).run();
            return;
        }
        runUiOrStoreChecks();
    }

    private boolean runProgressChecks() {
        if (reminderFixture != null) {
            new ReminderDeviceChecks().run(this, reminderFixture);
            return true;
        }
        if (progressConflictUi) {
            new ProgressLifecycleChecks(this).runConflict();
            return true;
        }
        if (progressLifecycle) {
            new ProgressLifecycleChecks(this).run();
            return true;
        }
        if (recoveryFixture) {
            new RecoveryDeviceFixture().run(this);
            return true;
        }
        if (progressFixture) {
            new ProgressDeviceFixture().run(this);
            return true;
        }
        return false;
    }

    private void runUiOrStoreChecks() {
        if (runProgressChecks()) {
            return;
        }
        if (configuration) {
            new ConfigurationChecks(this).run();
            return;
        }
        if (saveLifecycle) {
            new SaveLifecycleChecks(this).run();
            return;
        }
        if (calendarFixture) {
            new CalendarDeviceFixture().run(this);
            return;
        }
        if (uiState) {
            new UiStateChecks().run(this);
            return;
        }
        if (blePeer != null) {
            new BleDeviceChecks(this).run(blePeer, bleRecovery, bleAwakeCpu, bleForeground, bleProgress);
            return;
        }
        runStoreChecks();
    }

    private void runStoreChecks() {
        startDiagnostics(Thread.currentThread());
        String prefix = "schedule-test-" + UUID.randomUUID();
        String phoneName = prefix + "-phone.db";
        String watchName = prefix + "-watch.db";
        String upgradeName = prefix + "-upgrade.db";
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            try (Store phone = new Store(getTargetContext(), phoneName, true);
                Store watch = new Store(getTargetContext(), watchName, false)) {
                Plan p = new Plan();
                p.title = "合成测试";
                p.anchor = LocalDate.now().plusDays(1).toString();
                p.rule = Rule.DAYS;
                p.count = 5;
                phone.save(p);
                byte[] first = phone.outgoing();
                Wire.Ack ack = watch.receive(first);
                check(watch.plans().size() == 1 && watch.instances().size() == 5);
                watch.receive(first);
                check(watch.plans().size() == 1 && watch.instances().size() == 5);
                Wire.Ack wrong = Wire.copy(ack, Wire.Ack.class);
                wrong.digest = "invalid";
                try {
                    phone.acknowledge(wrong);
                    throw new AssertionError("Wrong ACK accepted");
                } catch (IllegalArgumentException expected) {
                    checks++;
                }
                check(phone.pending() > 0);
                phone.acknowledge(ack);
                check(phone.pending() == 0);
                Instance original = watch.instances().get(0);
                watch.record(original, Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
                p.startMinute += 60;
                phone.save(p);
                watch.receive(phone.outgoing());
                check(watch.instances().get(0).start == original.start);
                phone.receive(watch.outgoing());
                check(phone.instances().get(0).start == original.start);
                Wire.Snapshot conflict = phone.snapshot(false);
                conflict.plans.get(0).title = "应回滚";
                conflict.plans.get(0).version++;
                conflict.events.get(0).logical++;
                try {
                    watch.receive(Wire.encode(conflict));
                    throw new AssertionError("Collision accepted");
                } catch (IllegalArgumentException expected) {
                    checks++;
                }
                check(watch.plans().get(0).title.equals("合成测试"));
                phone.restore(Wire.encode(phone.snapshot(true)));
                check(phone.events().size() == 1);
                backupTransaction(phone);
                adviceTransaction(phone);
            }
            resumeAndTypeSync(phoneName, watchName);
            checks += new BatchStoreChecks(getTargetContext()).run();
            checks += new CalendarMigrationChecks(getTargetContext()).run();
            checks += new ProgressStoreChecks(getTargetContext()).run();
            checks += LocalDataChecks.run(getTargetContext());
            checks += ReminderWorkChecks.run();
            checks += ReminderScopeStoreChecks.run(getTargetContext());
            try (SQLiteDatabase db = getTargetContext().openOrCreateDatabase(upgradeName, 0, null);
                InputStream stream = getTargetContext().getAssets().open("schema-v1.sql")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                for (String sql : out.toString("UTF-8").split(";")) {
                    if (!sql.trim().isEmpty()) {
                        db.execSQL(sql);
                    }
                }
                db.execSQL("INSERT INTO meta(key,value) VALUES ('synthetic','retained')");
                db.setVersion(1);
            }
            try (Store upgraded = new Store(getTargetContext(), upgradeName, true)) {
                check(upgraded.getReadableDatabase().getVersion() == 6);
                check(upgraded.meta("synthetic", "").equals("retained"));
            }
            result.putString("stream", "Synthetic Android Store checks passed: " + checks);

        } catch (Throwable error) {
            StackTraceElement point = error.getStackTrace().length == 0 ? null : error.getStackTrace()[0];
            result.putString("stream", "Synthetic Android Store checks failed after " + checks + "; type=" + error
                    .getClass().getSimpleName() + "; location=" + (point == null ? "unknown" : point.getFileName()
                    + ":" + point.getLineNumber()));
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            for (String name : new String[] { phoneName, watchName, upgradeName }) {
                getTargetContext().deleteDatabase(name);
            }
        }
        // finish may terminate the process immediately; cleanup must precede it.
        finish(resultCode, result);
    }

    private void startDiagnostics(Thread tested) {
        Thread diagnostics = new Thread(() -> {
            try {
                Thread.sleep(30000);
                StringBuilder trace = new StringBuilder("Synthetic Store test still running; worker stack:\n");
                for (StackTraceElement frame : tested.getStackTrace()) {
                    trace.append(frame).append('\n');
                }
                Bundle result = new Bundle();
                result.putString("stream", trace.toString());
                sendStatus(0, result);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "schedule-test-diagnostics");
        diagnostics.setDaemon(true);
        diagnostics.start();
    }
}
