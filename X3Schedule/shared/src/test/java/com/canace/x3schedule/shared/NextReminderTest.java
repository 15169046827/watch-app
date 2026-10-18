package com.canace.x3schedule.shared;

import static org.junit.Assert.assertEquals;

import com.canace.x3schedule.domain.Reminders;
import org.junit.Test;

public class NextReminderTest {
    @Test
    public void eachExplicitFutureReminderUsesItsOwnInstanceAfterQuietHours() {
        for (Reminders.Purpose purpose : new Reminders.Purpose[] {
            Reminders.Purpose.START, Reminders.Purpose.END, Reminders.Purpose.CONFIRMATION }) {
            NextReminder next = new NextReminder();
            next.offer(new Reminders.Due("explicit", "selected", 200, purpose), 500, 100);
            next.offer(new Reminders.Due("later", "other", 600, purpose), 600, 100);
            assertEquals(500, next.clockAt);
            assertEquals("selected", next.clockInstanceId);
        }
    }

    @Test
    public void automaticRetryAndExpiredReminderCannotWakeAsAlarmClock() {
        NextReminder retry = new NextReminder();
        retry.offer(new Reminders.Due("retry", "id", 200, Reminders.Purpose.RETRY), 200, 100);
        assertEquals(Long.MAX_VALUE, retry.clockAt);
        NextReminder expired = new NextReminder();
        expired.offer(new Reminders.Due("old", "id", 50, Reminders.Purpose.START), 200, 100);
        assertEquals(Long.MAX_VALUE, expired.clockAt);
    }

    @Test
    public void simultaneousUserReminderWinsRegardlessOfOrder() {
        for (boolean reverse : new boolean[] { false, true }) {
            NextReminder next = new NextReminder();
            Reminders.Due user = new Reminders.Due("end", "user", 200, Reminders.Purpose.END);
            Reminders.Due retry = new Reminders.Due("retry", "retry", 200);
            next.offer(reverse ? retry : user, 200, 100);
            next.offer(reverse ? user : retry, 200, 100);
            assertEquals(200, next.clockAt);
            assertEquals("user", next.clockInstanceId);
        }
    }

    @Test
    public void earlierRetryIsNotPromotedByLaterUserReminder() {
        NextReminder next = new NextReminder();
        next.offer(new Reminders.Due("retry", "retry", 200), 200, 100);
        next.offer(new Reminders.Due("start", "user", 300, Reminders.Purpose.START), 300, 100);
        assertEquals(200, next.at);
        assertEquals(300, next.clockAt);
        assertEquals("user", next.clockInstanceId);
    }

    @Test
    public void catchUpIsOrdinaryButDoesNotReplaceEarlierScheduledReminder() {
        NextReminder later = new NextReminder();
        later.offer(new Reminders.Due("start", "user", 2000, Reminders.Purpose.START), 2000, 100);
        later.catchUp(100);
        assertEquals(1100, later.at);
        assertEquals(2000, later.clockAt);
        NextReminder earlier = new NextReminder();
        earlier.offer(new Reminders.Due("start", "user", 500, Reminders.Purpose.START), 500, 100);
        earlier.catchUp(100);
        assertEquals(500, earlier.at);
        assertEquals(500, earlier.clockAt);
    }
}
