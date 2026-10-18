package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Real configuration changes in the isolated phone app, including a pending production save. */
final class ConfigurationChecks {
    private final Instrumentation runner;
    private Activity activity;
    private int originalOrientation;
    private int checks;

    ConfigurationChecks(Instrumentation runner) {
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
                int before = store.plans().size();
                String events = Wire.JSON.toJson(store.events());
                launch();
                calendar();
                Plan draft = draft();
                editor(draft);
                preview(store, draft);
                check(store.plans().size() == before + 1, "Exactly one plan saved across rotation");
                check(events.equals(Wire.JSON.toJson(store.events())), "Historical events unchanged");
            }
            result.putString("stream", "Configuration checks passed: " + checks);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Configuration checks interrupted");
        } catch (IOException | RuntimeException | AssertionError error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Configuration checks failed at " + checks + ": " + error.getMessage());
        } finally {
            if (activity != null) {
                runner.runOnMainSync(() -> {
                    activity.setRequestedOrientation(originalOrientation);
                    activity.finish();
                });
            }
        }
        runner.finish(code, result);
    }

    private void launch() throws IOException {
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(ScheduleActivity.class.getName(), null, false);
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(runner.getUiAutomation()
                .executeShellCommand("am start -f 0x10008000 -n " + runner.getTargetContext().getPackageName()
                        + "/com.canace.x3schedule.shared.ScheduleActivity"))) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int size;
            while ((size = output.read(buffer)) != -1) {
                total += size;
                require(total <= 8192, "Unexpected Activity launch output size");
            }
            activity = monitor.waitForActivityWithTimeout(15000);
        } finally {
            runner.removeMonitor(monitor);
        }
        check(activity != null, "Validation Activity launched");
        originalOrientation = ui(() -> activity.getRequestedOrientation());
    }

    private void calendar() throws InterruptedException {
        rotate(false);
        LocalDate selected = LocalDate.now().plusDays(2);
        ui(() -> {
            set("calendarDay", selected);
            set("calendarMode", 2);
            invoke("home", new Class<?>[0]);
            return null;
        });
        await(() -> ui(() -> !calendarLoading() && timeline() != null && columnWidth() > 0),
                "Portrait calendar ready");
        calendarRefresh();
        int portrait = ui(this::columnWidth);
        rotate(true);
        await(() -> ui(() -> !calendarLoading() && columnWidth() > portrait),
                "Day column expands after rotation");
        check(ui(() -> selected.equals(get("calendarDay")) && (int) get("calendarMode") == 2),
                "Selected date and day mode retained");
        ui(() -> {
            set("calendarMode", 1);
            invoke("home", new Class<?>[0]);
            return null;
        });
        await(() -> ui(() -> !calendarLoading() && timeline() != null && timeline().getWidth() > 0),
                "Week calendar ready");
        ui(() -> {
            timeline().scrollTo(ScheduleUi.dp(activity, 280), 0);
            return null;
        });
        await(() -> ui(() -> (int) get("calendarOffset") > 0), "Week scroll recorded");
        int offset = ui(() -> (int) get("calendarOffset"));
        rotate(false);
        await(() -> ui(() -> !calendarLoading() && timeline() != null && Math.abs(timeline().getScrollX()
                - ScheduleUi.dp(activity, offset)) <= 1), "Week horizontal position restored");
        check(ui(() -> selected.equals(get("calendarDay")) && (int) get("calendarMode") == 1),
                "Week selection retained");
    }

    private void calendarRefresh() throws InterruptedException {
        View previous = ui(this::timeline);
        LocalDate selected = ui(() -> (LocalDate) get("calendarDay"));
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = (ExecutorService) field(CalendarScreen.class, "WORKER", null);
        worker.execute(() -> {
            blocked.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            check(blocked.await(5, TimeUnit.SECONDS), "Calendar worker blocked");
            ui(() -> {
                set("calendarDay", selected.plusDays(1));
                invoke("home", new Class<?>[0]);
                set("calendarDay", selected.plusDays(2));
                invoke("home", new Class<?>[0]);
                return null;
            });
            check(ui(() -> timeline() == previous && previous.isAttachedToWindow()),
                    "Refresh retains the previous calendar through consecutive requests");
            await(() -> ui(() -> find(content(), view -> view instanceof ProgressBar
                    && view.isShown() && view.getGlobalVisibleRect(new Rect())) != null),
                    "Delayed calendar loading indicator visible within the viewport");
        } finally {
            release.countDown();
        }
        await(() -> ui(() -> timeline() != previous && columnWidth() > 0
                && find(content(), view -> view instanceof ProgressBar && view.isShown()) == null),
                "Newest calendar replaces the old view and removes the mask");
        View refreshed = ui(this::timeline);
        ui(() -> {
            set("calendarDay", selected);
            invoke("home", new Class<?>[0]);
            return null;
        });
        await(() -> ui(() -> timeline() != refreshed && columnWidth() > 0),
                "Calendar restored after refresh checks");
    }

    private boolean calendarLoading() {
        CalendarSurface surface = (CalendarSurface) get("calendarSurface");
        return surface == null || surface.loading();
    }

    private void editor(Plan draft) throws InterruptedException {
        ScreenSession before = ui(() -> {
            invoke("editor", new Class<?>[] { Plan.class }, draft);
            EditText title = (EditText) find(content(), view -> view instanceof EditText
                    && "日程名称".contentEquals(view.getContentDescription()));
            check(title != null, "Real editor opened");
            title.setText("旋转后保留的草稿");
            ScreenSession state = new ScreenSession();
            FormState.capture(content(), state);
            return state;
        });
        View original = ui(this::content);
        rotate(true);
        check(ui(() -> {
            ScreenSession after = new ScreenSession();
            FormState.capture(content(), after);
            return content() == original && before.fields.equals(after.fields)
                    && before.choices.equals(after.choices);
        }), "Editor fields and choices survive rotation");
    }

    private void preview(Store store, Plan draft) throws InterruptedException {
        ui(() -> {
            invoke("preview", new Class<?>[] { List.class, boolean.class, int.class },
                    Collections.singletonList(draft), false, 0);
            return null;
        });
        await(() -> ui(() -> confirm() != null), "Real preview ready");
        Button button = ui(this::confirm);
        rotate(false);
        check(ui(() -> confirm() == button && button.isEnabled()), "Unconfirmed preview retained");
        check(!exists(store, draft), "Rotation does not save an unconfirmed preview");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = (ExecutorService) field(PlanPreviewScreen.class, "WORKER", null);
        worker.execute(() -> {
            blocked.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            check(blocked.await(5, TimeUnit.SECONDS), "Save worker blocked");
            ui(() -> button.performClick());
            rotate(true);
            check(ui(() -> button.isAttachedToWindow() && !button.isEnabled()
                    && "preview".equals(get("page"))), "Pending save stays disabled across rotation");
            check(!exists(store, draft), "Save still queued before barrier release");
        } finally {
            release.countDown();
        }
        await(() -> exists(store, draft), "Save completes after rotation");
        await(() -> ui(() -> "home".equals(get("page"))), "Saved preview navigates home once complete");
        check(store.instances().stream().filter(item -> item.planId.equals(draft.id)).count() == 1,
                "Saved instance unique");
    }

    private void rotate(boolean landscape) throws InterruptedException {
        ui(() -> {
            activity.setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            return null;
        });
        await(() -> ui(() -> activity.getResources().getConfiguration().orientation
                == (landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT)),
                "Requested orientation applied");
        runner.waitForIdleSync();
    }

    private Plan draft() {
        Plan plan = new Plan();
        plan.title = "旋转期间确认保存";
        plan.kind = Kind.REMINDER;
        plan.rule = Rule.DAYS;
        plan.anchor = LocalDate.now().plusDays(11).toString();
        plan.count = 1;
        plan.reminderTargets = 0;
        return plan;
    }

    private boolean exists(Store store, Plan draft) {
        return store.plans().stream().anyMatch(plan -> plan.id.equals(draft.id));
    }

    private View content() {
        return (View) get("content");
    }

    private HorizontalScrollView timeline() {
        return (HorizontalScrollView) find(content(), view -> view instanceof HorizontalScrollView);
    }

    private int columnWidth() {
        HorizontalScrollView timeline = timeline();
        return timeline == null ? 0 : ((ViewGroup) timeline.getChildAt(0)).getChildAt(1).getWidth();
    }

    private Button confirm() {
        return (Button) find(content(), view -> view instanceof Button
                && "确认保存".contentEquals(((Button) view).getText()));
    }

    private View find(View root, Predicate<View> predicate) {
        if (predicate.test(root)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int n = 0; n < group.getChildCount(); n++) {
                View found = find(group.getChildAt(n), predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object get(String name) {
        return field(ScheduleActivity.class, name, activity);
    }

    private Object field(Class<?> type, String name, Object owner) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private void set(String name, Object value) {
        try {
            Field field = ScheduleActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private void invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = ScheduleActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private <T> T ui(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        runner.runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50);
        }
        check(condition.getAsBoolean(), message);
    }

    private void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
