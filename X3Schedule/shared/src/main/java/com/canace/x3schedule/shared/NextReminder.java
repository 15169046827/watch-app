package com.canace.x3schedule.shared;

import com.canace.x3schedule.domain.Reminders;

/** Chooses one next reminder; catch-up and automatic retries never become alarm clocks. */
final class NextReminder {
    long at = Long.MAX_VALUE;
    long clockAt = Long.MAX_VALUE;
    String clockInstanceId;

    void offer(Reminders.Due due, long deliveryAt, long now) {
        boolean eligible = due.purpose != Reminders.Purpose.RETRY && due.at > now;
        if (eligible && deliveryAt < clockAt) {
            clockAt = deliveryAt;
            clockInstanceId = due.instanceId;
        }
        at = Math.min(at, deliveryAt);
    }

    void catchUp(long now) {
        at = Math.min(at, now + 1000);
    }
}
