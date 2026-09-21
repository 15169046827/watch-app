package com.canace.x3voiceledger.phone;

public final class ScreenshotRetryPolicy {
    public static final int MAX_ATTEMPTS = 3;

    private ScreenshotRetryPolicy() { }

    public static int nextAttempt(long mediaId, long previousMediaId, int previousAttempts) {
        return mediaId == previousMediaId ? previousAttempts + 1 : 1;
    }

    public static boolean shouldRetry(int attempts) {
        return attempts < MAX_ATTEMPTS;
    }
}
