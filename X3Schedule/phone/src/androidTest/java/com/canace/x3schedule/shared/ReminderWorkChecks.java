package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verifies the queue and maintenance barrier without reading or changing any application database. */
final class ReminderWorkChecks {
    private ReminderWorkChecks() {
    }

    static int run() throws InterruptedException {
        require(Looper.myLooper() != Looper.getMainLooper(), "Checks must run off main thread");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicBoolean offMain = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean barrierOnMain = new AtomicBoolean();
        ReminderWork.execute(() -> {
            offMain.set(Looper.myLooper() != Looper.getMainLooper());
            entered.countDown();
            awaitRelease(release);
        }, success -> completed.set(success && Looper.myLooper() == Looper.getMainLooper()));
        try {
            require(entered.await(10, TimeUnit.SECONDS), "Reminder worker did not start");
            ReminderWork.drainForMaintenance(() -> {
                barrierOnMain.set(Looper.myLooper() == Looper.getMainLooper());
                drained.countDown();
            });
            require(drained.getCount() == 1 && !completed.get(), "Barrier overtook reminder work");
        } finally {
            release.countDown();
        }
        require(drained.await(10, TimeUnit.SECONDS) && offMain.get() && completed.get() && barrierOnMain.get(),
                "Reminder queue or main-thread completion failed");
        CountDownLatch failed = new CountDownLatch(1);
        AtomicBoolean failureReported = new AtomicBoolean();
        ReminderWork.execute(() -> {
            throw new IllegalArgumentException("Synthetic reminder failure");
        }, success -> {
            failureReported.set(!success);
            failed.countDown();
        });
        require(failed.await(10, TimeUnit.SECONDS) && failureReported.get(), "Reminder failure was not reported");
        CountDownLatch continued = new CountDownLatch(1);
        ReminderWork.execute(() -> { }, success -> {
            if (success) {
                continued.countDown();
            }
        });
        require(continued.await(10, TimeUnit.SECONDS), "Failure stopped subsequent reminder work");
        return 5;
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            require(release.await(10, TimeUnit.SECONDS), "Synthetic worker was not released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
