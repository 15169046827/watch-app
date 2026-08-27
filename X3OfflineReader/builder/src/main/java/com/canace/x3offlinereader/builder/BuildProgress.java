package com.canace.x3offlinereader.builder;

/** Progress is 0..100. A null message updates only the bar; non-null messages are milestones. */
@FunctionalInterface
public interface BuildProgress {
    BuildProgress NONE=(percent,message)->{};
    void update(int percent,String message);
    default void estimateSeconds(long seconds) {}
}
