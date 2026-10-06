package com.canace.x3schedule.shared;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Keeps reminder database work off component callbacks and drains it before local maintenance. */
final class ReminderWork {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-reminders"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ReminderWork() {
    }

    static void request(Context context, boolean deliver, Consumer<Boolean> finished) {
        Context application = context.getApplicationContext();
        execute(() -> {
            if (!DataMaintenance.active()) {
                ReminderReceiver.schedule(application, deliver);
            }
        }, finished);
    }

    static void execute(Runnable operation, Consumer<Boolean> finished) {
        WORKER.execute(() -> {
            boolean success = false;
            try {
                operation.run();
                success = true;
            } catch (RuntimeException error) {
                // The caller decides whether to retry or disclose failure; never crash the worker.
            } finally {
                boolean result = success;
                MAIN.post(() -> finished.accept(result));
            }
        });
    }

    static void drainForMaintenance(Runnable drained) {
        WORKER.execute(() -> MAIN.post(drained));
    }
}
