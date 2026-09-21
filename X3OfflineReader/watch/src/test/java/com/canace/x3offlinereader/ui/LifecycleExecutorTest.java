package com.canace.x3offlinereader.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LifecycleExecutorTest {
    @Test
    void acceptsWorkBeforeShutdown() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            assertTrue(LifecycleExecutor.execute(executor, ran::countDown));
            assertTrue(ran.await(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void quietlyRejectsLateLifecycleCallback() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        assertFalse(LifecycleExecutor.execute(executor, () -> { }));
    }
}
