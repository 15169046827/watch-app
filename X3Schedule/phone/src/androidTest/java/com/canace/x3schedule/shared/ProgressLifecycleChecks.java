package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.RecoveryPlanner;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real dialogs and async save across replacement of the original progress section. */
final class ProgressLifecycleChecks {
    private final Instrumentation runner;
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final AtomicInteger changes = new AtomicInteger();
    private Activity activity;
    private LinearLayout parent;
    private Instance item;
    private ProgressScreen first;

    ProgressLifecycleChecks(Instrumentation runner) {
        this.runner = runner;
    }

    void run() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(runner.getTargetContext().getPackageName()
                    .equals("com.canace.x3schedule.phone.validation.phone"), "Validation phone only");
            try (Store store = new Store(runner.getTargetContext());
                    RecoveryControl control = new RecoveryControl(runner, true)) {
                require(store.meta("peer", "").isEmpty(), "Unpaired validation required");
                item = seed(store);
                activity = control.activity();
                runner.runOnMainSync(() -> {
                    parent = new LinearLayout(activity);
                    parent.setOrientation(LinearLayout.VERTICAL);
                    activity.setContentView(parent);
                    first = show();
                });
                click("反馈原时段行动与成果");
                click("● 最低行动");
                click("预览并确认");
                click("确定");
                await(() -> changes.get() == 2 && contains("↻ 正式补救"), "Automatic offer did not refresh");
                Progress.Summary state = Progress.summarize(item, store.events());
                require(state.enabled && state.original == Progress.Grade.MINIMUM, "Recovery was not saved");
                current.set(false);
                Progress.Entry cancel = new Progress.Entry();
                cancel.op = Progress.Op.CANCEL;
                cancel.session = Progress.Session.SCHEDULED;
                cancel.recoveryParent = state.recoveryHead;
                Method commit = ProgressScreen.class.getDeclaredMethod("commit", Progress.Entry.class);
                commit.setAccessible(true);
                runner.runOnMainSync(() -> invoke(commit, cancel));
                await(() -> !Progress.summarize(item, store.events()).enabled, "Cancellation was not saved");
                runner.waitForIdleSync();
                require(changes.get() == 2, "Save navigated after leaving the task page");
                result.putString("stream", "Progress lifecycle PASS: automatic offer refresh; "
                        + "no navigation after leave");
            }
        } catch (Exception | AssertionError error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Progress lifecycle failed: " + error.getMessage());
        }
        runner.finish(code, result);
    }

    private Instance seed(Store store) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        require(now.getHour() >= 5 && now.getHour() < 23, "Run between 05:00 and 23:00");
        ZonedDateTime original = now.minusMinutes(10);
        Plan plan = new Plan();
        plan.title = "QA07-Progress-Lifecycle";
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
        plan.progress.recoveryMinute = now.plusMinutes(30).getHour() * 60 + now.plusMinutes(30).getMinute();
        plan.progress.recoveryDuration = 1;
        store.save(plan);
        return store.instances().stream().filter(i -> i.planId.equals(plan.id)).findFirst().get();
    }

    void runConflict() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            String name = runner.getTargetContext().getPackageName();
            require(name.equals("com.canace.x3schedule.phone.validation.phone")
                    || name.equals("com.canace.x3schedule.validation"), "Validation only");
            try (Store store = new Store(runner.getTargetContext(), "schedule.db", true);
                    RecoveryControl control = new RecoveryControl(runner, true)) {
                require(store.meta("peer", "").isEmpty(), "Unpaired validation required");
                item = seed(store);
                Plan occupied = new Plan();
                occupied.title = "QA07-Progress-Occupied";
                occupied.anchor = item.taskDay;
                occupied.rule = Rule.DAYS;
                occupied.count = 1;
                occupied.startMinute = item.progress.recoveryMinute;
                occupied.durationMinutes = 3;
                occupied.inheritReminders = false;
                occupied.reminderTargets = 0;
                store.save(occupied);
                Progress.Entry original = new Progress.Entry();
                original.op = Progress.Op.RESULT;
                original.grade = Progress.Grade.MINIMUM;
                store.recordProgress(item, original);
                activity = control.activity();
                runner.runOnMainSync(() -> {
                    parent = new LinearLayout(activity);
                    parent.setOrientation(LinearLayout.VERTICAL);
                    activity.setContentView(parent);
                    first = show();
                });
                await(() -> contains("启用备用补救时段"), "Progress screen not ready");
                long[] window = RecoveryPlanner.standby(item);
                Method preview = ProgressScreen.class.getDeclaredMethod("preview", long.class, long.class,
                        Progress.Op.class);
                preview.setAccessible(true);
                runner.runOnMainSync(() -> preview(preview, window));
                await(() -> dialogContains("补救时段未能开启"), "Missing conflict dialog");
                require(dialogContains("存在日程冲突。请前往手机处理后重试。"), "Missing phone guidance");
                require(!Progress.summarize(item, store.events()).enabled, "Conflict enabled recovery");
                Bitmap bitmap = runner.getUiAutomation().takeScreenshot();
                require(bitmap != null, "Missing conflict screenshot");
                try (FileOutputStream output = new FileOutputStream(new File(
                        runner.getTargetContext().getExternalFilesDir(null), "x3schedule-conflict-ui.png"))) {
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Screenshot write failed");
                } finally {
                    bitmap.recycle();
                }
                result.putString("stream", "Progress conflict UI PASS; blocked; phone guidance; screenshot saved");
            }
        } catch (Exception | AssertionError error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Progress conflict UI failed: " + error.getMessage());
        }
        runner.finish(code, result);
    }

    private void preview(Method method, long[] window) {
        try {
            method.invoke(first, window[0], window[1], Progress.Op.ENABLE);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean dialogContains(String prefix) {
        AccessibilityNodeInfo root = runner.getUiAutomation().getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        try {
            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(prefix);
            boolean found = !nodes.isEmpty();
            for (AccessibilityNodeInfo node : nodes) {
                node.recycle();
            }
            return found;
        } finally {
            root.recycle();
        }
    }

    private ProgressScreen show() {
        ProgressScreen screen = new ProgressScreen(activity, parent, item, () -> {
            changes.incrementAndGet();
            parent.removeAllViews();
            show();
        }, current::get);
        screen.show();
        return screen;
    }

    private void invoke(Method method, Progress.Entry entry) {
        try {
            method.invoke(first, entry);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private void click(String prefix) {
        await(() -> {
            AccessibilityNodeInfo root = runner.getUiAutomation().getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            try {
                return clickNode(root, prefix);
            } finally {
                root.recycle();
            }
        }, "Missing control: " + prefix);
    }

    private boolean clickNode(AccessibilityNodeInfo node, String prefix) {
        if (node.isClickable() && String.valueOf(node.getText()).startsWith(prefix)) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int n = 0; n < node.getChildCount(); n++) {
            AccessibilityNodeInfo child = node.getChild(n);
            if (child != null) {
                try {
                    if (clickNode(child, prefix)) {
                        return true;
                    }
                } finally {
                    child.recycle();
                }
            }
        }
        return false;
    }

    private boolean contains(String prefix) {
        boolean[] found = { false };
        runner.runOnMainSync(() -> found[0] = contains(parent, prefix));
        return found[0];
    }

    private boolean contains(View view, String prefix) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(prefix)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int n = 0; n < group.getChildCount(); n++) {
                if (contains(group.getChildAt(n), prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            SystemClock.sleep(100);
        }
        throw new IllegalArgumentException(message);
    }
}
