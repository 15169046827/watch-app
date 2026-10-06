package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import com.canace.x3schedule.domain.Batch;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.LocalDate;
import java.util.UUID;

/** Real bonded BLE, but only synthetic databases; invoked explicitly with a peer argument. */
final class BleDeviceChecks {
    private final Instrumentation instrumentation;
    private final SyntheticBleContext context;

    BleDeviceChecks(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = new SyntheticBleContext(instrumentation.getTargetContext());
    }

    void run(String peer, String recoveryId, boolean awakeCpu, boolean foreground) {
        String name = "schedule-test-ble-" + UUID.randomUUID() + ".db";
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        boolean phone = context.getPackageName().endsWith(".phone");
        PowerManager.WakeLock wakeLock = null;
        try (Store store = new Store(context, name, phone)) {
            require(context.getPackageName().equals("com.canace.x3schedule.phone.validation.phone")
                    || context.getPackageName().equals("com.canace.x3schedule.validation"),
                    "Synthetic BLE requires an isolated validation application");
            require(context.getString(R.string.schedule_ble_service).endsWith("ac001")
                    && context.getString(R.string.schedule_ble_data).endsWith("ac002"),
                    "Synthetic BLE namespace is not isolated");
            require(peer != null && peer.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"), "Invalid peer");
            require(recoveryId == null || recoveryId.matches("[a-f0-9]{32}"), "Invalid recovery ID");
            if (awakeCpu) {
                require(recoveryId != null, "CPU control requires recovery mode");
                wakeLock = diagnosticCpu();
            }
            store.putMeta("peer", peer);
            if (phone) {
                seed(store);
            }
            long started = System.currentTimeMillis();
            try (RecoveryControl control = new RecoveryControl(instrumentation, foreground);
                    BleLink link = new BleLink(context, store, () -> { }, () -> { })) {
                link.start();
                await(store, link, started);
                // Give the final ATT response time to reach the other endpoint before teardown.
                Thread.sleep(2000);
                if (recoveryId != null) {
                    new BleRecoveryChecks(instrumentation, context, recoveryId, foreground).run(store, link);
                }
                require(context.isolated(), "Non-test database accessed during BLE check");
                result.putString("stream", "Synthetic bonded BLE passed; role=" + (phone ? "phone" : "watch")
                        + "; elapsedMs=" + (System.currentTimeMillis() - started)
                        + "; plans=" + store.plans().size() + "; events=" + store.events().size()
                        + "; recovery=" + (recoveryId != null));
            }
            // BleLink cleanup is serialized on its own worker before the database closes.
            Thread.sleep(1000);
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Synthetic bonded BLE failed; type=" + error.getClass().getSimpleName()
                    + "; detail=" + error.getMessage());
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (awakeCpu) {
                instrumentation.getUiAutomation().dropShellPermissionIdentity();
            }
            context.deleteDatabase(name);
        }
        instrumentation.finish(code, result);
    }

    private PowerManager.WakeLock diagnosticCpu() {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IllegalArgumentException("CPU diagnostic requires Android 10 or newer");
        }
        instrumentation.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.WAKE_LOCK);
        PowerManager.WakeLock lock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "X3Schedule:ble-diagnostic");
        lock.acquire(600000);
        return lock;
    }

    private void seed(Store store) {
        Plan execution = new Plan();
        execution.title = "合成 BLE 分批验收";
        execution.rule = Rule.DAYS;
        execution.anchor = LocalDate.now().minusDays(32).toString();
        execution.count = 80;
        execution.durationMinutes = 26 * 60;
        for (int index = 0; index < 10; index++) {
            execution.steps.add(new String(new char[80]).replace('\0', '测'));
        }
        store.save(execution);
        Plan todo = new Plan();
        todo.title = "合成 BLE 待办验收";
        todo.kind = Kind.TODO;
        todo.rule = Rule.DAYS;
        todo.count = 1;
        todo.durationMinutes = 0;
        todo.dateOnly = true;
        todo.todoDue = LocalDate.now().plusDays(1).toString();
        store.save(todo);
        Plan allDay = new Plan();
        allDay.title = "合成 BLE 多日全天验收";
        allDay.kind = Kind.REMINDER;
        allDay.dateOnly = true;
        allDay.daySpan = 3;
        allDay.durationMinutes = 0;
        allDay.rule = Rule.MONTH_DAY;
        allDay.anchor = LocalDate.now().plusDays(1).toString();
        allDay.count = 2;
        store.save(allDay);
        Settings settings = store.settings();
        settings.dateOnlyReminderMinute = 615;
        store.settings(settings);
        require(Batch.decode(store.outgoingBatch()).manifest.count >= 2, "Fixture must cross pages");
    }

    private void await(Store store, BleLink link, long started) throws InterruptedException {
        boolean recorded = false;
        while (System.currentTimeMillis() - started < 240000) {
            if (!store.phone && !recorded) {
                for (Instance instance : store.instances()) {
                    if (instance.kind == Kind.TODO && !instance.cancelled) {
                        store.record(instance, Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
                        recorded = true;
                        break;
                    }
                }
            }
            boolean idle = link.status.equals("对方已确认本机数据") || link.status.equals("仍有新改动待同步");
            if (idle && !store.events().isEmpty() && (store.phone || (recorded && store.pending() == 0))) {
                require(store.plans().size() == 3 && store.events().size() == 1, "Unexpected merged fixture");
                calendarTypes(store);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timeout; status=" + link.status);
    }

    private static void calendarTypes(Store store) {
        require(store.settings().dateOnlyReminderMinute == 615, "Default date reminder did not transfer");
        for (Plan plan : store.plans()) {
            if (plan.kind == Kind.EXECUTION) {
                require(plan.durationMinutes == 1560, "Multi-day duration did not transfer");
            } else if (plan.kind == Kind.REMINDER) {
                require(plan.dateOnly && plan.daySpan == 3 && plan.rule == Rule.MONTH_DAY,
                        "All-day recurrence did not transfer");
            } else {
                require(plan.dateOnly && !plan.todoDue.isEmpty(), "Date deadline did not transfer");
            }
        }
    }
}
