package com.canace.x3offlinereader.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class LifecycleExecutor {
    private LifecycleExecutor() { }

    static boolean execute(Executor executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }
}
