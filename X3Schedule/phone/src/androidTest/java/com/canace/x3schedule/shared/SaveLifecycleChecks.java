package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real preview/save component with a real Activity and a deliberately delayed save worker. */
final class SaveLifecycleChecks {
    private final Instrumentation runner;
    private final AtomicInteger callbacks = new AtomicInteger();
    private Activity activity;
    private Button confirm;
    private int checks;

    SaveLifecycleChecks(Instrumentation runner) {
        this.runner = runner;
    }

    void run() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(runner.getTargetContext().getPackageName()
                    .equals("com.canace.x3schedule.phone.validation.phone"), "Validation phone only");
            try (Store store = new Store(runner.getTargetContext())) {
                require(store.meta("peer", "").isEmpty(), "Validation must not have a sync peer");
                String events = Wire.JSON.toJson(store.events());
                int before = store.plans().size();
                unconfirmed(store);
                confirmed(store);
                check(store.plans().size() == before + 1, "Exactly one plan committed");
                check(events.equals(Wire.JSON.toJson(store.events())), "Historical events unchanged");
                check(callbacks.get() == 0, "No navigation callback after Activity destruction");
            }
            result.putString("stream", "Save lifecycle checks passed: " + checks);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Save lifecycle checks interrupted");
        } catch (IOException | ReflectiveOperationException | RuntimeException | AssertionError error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Save lifecycle failed at " + checks + ": " + error.getMessage());
        } finally {
            if (activity != null) {
                runner.runOnMainSync(() -> activity.finish());
            }
        }
        runner.finish(code, result);
    }

    private void unconfirmed(Store store) throws InterruptedException, IOException {
        Plan draft = draft("未确认预览不保存");
        show(draft);
        check(!exists(store, draft.id), "Preview alone does not save");
        runner.runOnMainSync(() -> activity.finish());
        runner.waitForIdleSync();
        check(!exists(store, draft.id), "Closing unconfirmed preview does not save");
    }

    private void confirmed(Store store) throws InterruptedException, ReflectiveOperationException, IOException {
        Plan draft = draft("已确认保存后离页");
        show(draft);
        Field field = PlanPreviewScreen.class.getDeclaredField("WORKER");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(null);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        worker.execute(() -> {
            blocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        check(blocked.await(5, TimeUnit.SECONDS), "Save worker barrier reached");
        try {
            runner.runOnMainSync(() -> {
                confirm.performClick();
                check(!confirm.isEnabled(), "Confirmation disables repeat taps");
                activity.finish();
            });
            runner.waitForIdleSync();
            check(activity.isFinishing(), "Activity leaves before worker released");
            check(!exists(store, draft.id), "Worker barrier prevents early commit");
        } finally {
            release.countDown();
        }
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!exists(store, draft.id) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50);
        }
        check(exists(store, draft.id), "Confirmed save completes after leaving");
        check(store.instances().stream().filter(item -> item.planId.equals(draft.id)).count() == 1,
                "Committed instance is unique");
    }

    private void show(Plan draft) throws InterruptedException, IOException {
        phase("Launching preview Activity");
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(ScheduleActivity.class.getName(), null, false);
        String command = "am start -f 0x10008000 -n " + runner.getTargetContext().getPackageName()
                + "/com.canace.x3schedule.shared.ScheduleActivity";
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                runner.getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            int size;
            int total = 0;
            while ((size = output.read(buffer)) != -1) {
                total += size;
                require(total <= 8192, "Unexpected Activity launch output size");
            }
            activity = monitor.waitForActivityWithTimeout(15000);
        } finally {
            runner.removeMonitor(monitor);
        }
        check(activity != null, "Validation Activity launched");
        CountDownLatch loaded = new CountDownLatch(1);
        runner.runOnMainSync(() -> {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(root);
            new PlanPreviewScreen(activity, root, Collections.singletonList(draft), callbacks::incrementAndGet,
                    () -> {
                        for (int n = 0; n < root.getChildCount(); n++) {
                            if (root.getChildAt(n) instanceof Button) {
                                confirm = (Button) root.getChildAt(n);
                            }
                        }
                        loaded.countDown();
                    }).show();
        });
        check(loaded.await(15, TimeUnit.SECONDS) && confirm != null, "Preview becomes interactive");
        phase("Preview interactive");
    }

    private Plan draft(String title) {
        Plan plan = new Plan();
        plan.title = title;
        plan.kind = Kind.REMINDER;
        plan.rule = Rule.DAYS;
        plan.anchor = LocalDate.now().plusDays(10).toString();
        plan.count = 1;
        plan.reminderTargets = 0;
        return plan;
    }

    private boolean exists(Store store, String id) {
        return store.plans().stream().anyMatch(plan -> plan.id.equals(id));
    }

    private void phase(String text) {
        Bundle status = new Bundle();
        status.putString("stream", text);
        runner.sendStatus(0, status);
    }

    private void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
