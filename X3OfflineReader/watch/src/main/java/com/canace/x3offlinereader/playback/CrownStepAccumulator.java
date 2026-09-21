package com.canace.x3offlinereader.playback;

/** Converts a noisy, speed-dependent crown delta stream into bounded volume steps. */
public final class CrownStepAccumulator {

    static final float STEP_THRESHOLD = 1f;

    static final float REVERSAL_THRESHOLD = 1f;

    static final long MIN_STEP_INTERVAL_MS = 100;

    static final long SESSION_GAP_MS = 150;

    private int direction;

    private float accumulated;

    private float opposite;

    private long lastEvent = Long.MIN_VALUE;

    private long lastStep = Long.MIN_VALUE;

    /** Returns +1 to raise, -1 to lower, or 0 while accumulating/rate limiting. */
    public int add(float delta, long eventTimeMs) {
        if (!usable(delta)) {
            return 0;
        }
        beginEvent(eventTimeMs);
        if (!accumulate(delta) || !stepReady(eventTimeMs)) {
            return 0;
        }
        accumulated -= STEP_THRESHOLD;
        lastStep = eventTimeMs;
        return direction;
    }

    private static boolean usable(float delta) {
        return Float.isFinite(delta) && delta != 0f;
    }

    private void beginEvent(long eventTimeMs) {
        if (lastEvent != Long.MIN_VALUE && eventTimeMs - lastEvent >= SESSION_GAP_MS) {
            reset();
        }
        lastEvent = eventTimeMs;
    }

    private boolean accumulate(float delta) {
        int incoming = delta < 0f ? 1 : -1;
        float magnitude = Math.abs(delta);
        if (direction == 0) {
            direction = incoming;
            accumulated = magnitude;
            return true;
        }
        if (incoming == direction) {
            accumulated += magnitude;
            opposite = 0f;
            return true;
        }
        opposite += magnitude;
        if (opposite < REVERSAL_THRESHOLD) {
            return false;
        }
        direction = incoming;
        accumulated = opposite;
        opposite = 0f;
        return true;
    }

    private boolean stepReady(long eventTimeMs) {
        return accumulated >= STEP_THRESHOLD
                && (lastStep == Long.MIN_VALUE
                        || eventTimeMs - lastStep >= MIN_STEP_INTERVAL_MS);
    }

    public void reset() {
        direction = 0;
        accumulated = 0f;
        opposite = 0f;
        lastEvent = Long.MIN_VALUE;
        lastStep = Long.MIN_VALUE;
    }
}
