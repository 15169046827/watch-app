package com.canace.x3voiceledger;

import com.canace.x3voiceledger.phone.IncomingRevisionPolicy;
import com.canace.x3voiceledger.phone.ScreenshotRetryPolicy;

public final class DataConsistencySelfTest {
    private DataConsistencySelfTest() { }

    public static void main(String[] args) {
        locationSessionsRejectStaleCallbacks();
        revisionsNeverMoveBackwards();
        screenshotRetriesAreBounded();
        System.out.println("Data consistency self-tests passed.");
    }

    private static void locationSessionsRejectStaleCallbacks() {
        LocationSession session = new LocationSession();
        long first = session.begin();
        long second = session.begin();
        LocationSnapshot stale = new LocationSnapshot(1d, 2d, 3f, 4L);
        check(!session.accept(first, stale), "a stale location callback must be rejected");
        check(session.latest() == null, "a rejected callback must not populate the session");
        check(session.accept(second, stale), "the active session callback must be accepted");
    }

    private static void revisionsNeverMoveBackwards() {
        check(IncomingRevisionPolicy.shouldApply(20L, null), "a new id must be accepted");
        check(IncomingRevisionPolicy.shouldApply(20L, 20L), "an equal retry is idempotent");
        check(IncomingRevisionPolicy.shouldApply(21L, 20L), "a newer revision must apply");
        check(!IncomingRevisionPolicy.shouldApply(19L, 20L), "an older revision must be ignored");
    }

    private static void screenshotRetriesAreBounded() {
        int first = ScreenshotRetryPolicy.nextAttempt(7L, 0L, 0);
        int second = ScreenshotRetryPolicy.nextAttempt(7L, 7L, first);
        int third = ScreenshotRetryPolicy.nextAttempt(7L, 7L, second);
        check(ScreenshotRetryPolicy.shouldRetry(first), "first failure should retry");
        check(ScreenshotRetryPolicy.shouldRetry(second), "second failure should retry");
        check(!ScreenshotRetryPolicy.shouldRetry(third), "third failure must stop retrying");
        check(
                ScreenshotRetryPolicy.nextAttempt(8L, 7L, third) == 1,
                "a different screenshot starts a fresh retry budget");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
