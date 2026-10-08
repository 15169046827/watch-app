package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public class ReminderTest {

    @Test
    public void automaticFollowUpsRemainDifferentFromScheduledStartAndEnd() {
        List<Reminders.Due> due = Reminders.candidates(instance(), null, true, new Settings());
        assertEquals(Reminders.Purpose.START, due.get(0).purpose);
        assertEquals(Reminders.Purpose.END, due.get(1).purpose);
        assertEquals(Reminders.Purpose.RETRY, due.get(2).purpose);
        assertEquals(Reminders.Purpose.RETRY, due.get(3).purpose);
    }

    @Test
    public void explicitlyContinuingReplacesAutomaticRetriesWithUserConfirmation() {
        Instance instance = instance();
        Event continued = new Event();
        continued.action = Action.CONTINUE;
        continued.continueMinutes = 10;
        continued.confirmedAt = instance.end;
        List<Reminders.Due> due = Reminders.candidates(instance, continued, true, new Settings());
        assertEquals(2, due.size());
        assertEquals(Reminders.Purpose.CONFIRMATION, due.get(1).purpose);
        Settings daytime = new Settings();
        daytime.quietStart = daytime.quietEnd;
        due = Reminders.candidates(instance, continued, true, daytime);
        assertEquals(instance.end + 10 * 60000L, due.get(1).at);
    }

    private Instance instance() {
        Plan p = new Plan();
        p.title = "合成测试";
        p.anchor = "2026-09-16";
        p.startMinute = 23 * 60;
        return Recurrence.generate(p, LocalDate.parse(p.anchor), LocalDate.parse(p.anchor)).get(0);
    }

    @Test
    public void quietDelaysAndBatches() {
        Instance i = instance();
        List<Reminders.Due> d = Reminders.candidates(i, null, true, new Settings());
        assertEquals(4, d.size());
        assertEquals(d.get(0).at, d.get(3).at);
        assertEquals(7, Instant.ofEpochMilli(d.get(0).at).atZone(ZoneId.of(i.zone)).getHour());
    }

    @Test
    public void watchOnlySuppressesPhone() {
        Instance i = instance();
        i.reminderTargets = 2;
        assertTrue(Reminders.candidates(i, null, true, new Settings()).isEmpty());
        Settings legacy = new Settings();
        legacy.watchRemindersEnabled = true;
        assertTrue(Reminders.candidates(i, null, false, legacy).isEmpty());
    }

    @Test
    public void completedNoReminders() {
        Instance i = instance();
        Event e = new Event();
        e.action = Action.COMPLETE;
        assertTrue(Reminders.candidates(i, e, true, new Settings()).isEmpty());
    }

    private long at(String time) {
        return java.time.OffsetDateTime.parse(time).toInstant().toEpochMilli();
    }

    @Test
    public void delayedDaytimeReminderWaitsUntilQuietEnds() {
        long original = at("2026-09-25T21:00:00+08:00");
        Reminders.Due due = new Reminders.Due("synthetic:start", "synthetic", original);
        long wake = at("2026-09-25T23:30:00+08:00");
        long morning = at("2026-09-26T07:00:00+08:00");
        assertEquals(morning, Reminders.deliveryAt(due, wake, "Asia/Shanghai", new Settings()));
        assertEquals(morning, Reminders.deliveryAt(due, morning, "Asia/Shanghai", new Settings()));
        assertEquals(original, due.at);
        assertEquals("synthetic:start", due.key);
    }

    @Test
    public void quietStartIsInclusiveAndQuietEndIsExclusive() {
        Reminders.Due due = new Reminders.Due("key", "id", at("2026-09-25T21:00:00+08:00"));
        long morning = at("2026-09-26T07:00:00+08:00");
        assertEquals(morning, Reminders.deliveryAt(due, at("2026-09-25T22:00:00+08:00"),
                "Asia/Shanghai", new Settings()));
        assertEquals(morning, Reminders.deliveryAt(due, at("2026-09-26T06:59:59+08:00"),
                "Asia/Shanghai", new Settings()));
        assertEquals(morning, Reminders.deliveryAt(due, morning, "Asia/Shanghai", new Settings()));
    }

    @Test
    public void daytimeQuietWindowAndDisabledWindowAreRespected() {
        Settings settings = new Settings();
        settings.quietStart = 12 * 60;
        settings.quietEnd = 14 * 60;
        Reminders.Due due = new Reminders.Due("key", "id", at("2026-09-25T11:00:00+08:00"));
        long now = at("2026-09-25T13:00:00+08:00");
        assertEquals(at("2026-09-25T14:00:00+08:00"),
                Reminders.deliveryAt(due, now, "Asia/Shanghai", settings));
        settings.quietEnd = settings.quietStart;
        assertEquals(now, Reminders.deliveryAt(due, now, "Asia/Shanghai", settings));
    }

    @Test
    public void deliveryDoesNotExtendCatchUpExpiry() {
        Reminders.Due due = new Reminders.Due("key", "id", at("2026-09-24T23:00:00+08:00"));
        long night = at("2026-09-25T22:30:00+08:00");
        long morning = at("2026-09-26T07:00:00+08:00");
        assertEquals(morning, Reminders.deliveryAt(due, night, "Asia/Shanghai", new Settings()));
        assertEquals(Long.MAX_VALUE, Reminders.deliveryAt(due, morning, "Asia/Shanghai", new Settings()));
    }

    @Test
    public void catchUpBoundaryAllowsExactlyOneDayAndRejectsOlder() {
        Settings settings = new Settings();
        settings.quietStart = settings.quietEnd;
        Reminders.Due due = new Reminders.Due("key", "id", at("2026-09-24T12:00:00+08:00"));
        long boundary = at("2026-09-25T12:00:00+08:00");
        assertEquals(boundary, Reminders.deliveryAt(due, boundary, "Asia/Shanghai", settings));
        assertEquals(Long.MAX_VALUE, Reminders.deliveryAt(due, boundary + 1, "Asia/Shanghai", settings));
    }

    @Test
    public void futureReminderNeverBecomesDueEarly() {
        long now = at("2026-09-25T08:00:00+08:00");
        long future = at("2026-09-25T09:00:00+08:00");
        Reminders.Due due = new Reminders.Due("key", "id", future);
        assertEquals(future, Reminders.deliveryAt(due, now, "Asia/Shanghai", new Settings()));
    }

    @Test
    public void deliveryUsesInstanceZoneAcrossDaylightSavingChange() {
        Reminders.Due due = new Reminders.Due("key", "id", at("2026-10-31T21:00:00-04:00"));
        long wake = at("2026-10-31T23:00:00-04:00");
        long morning = at("2026-11-01T07:00:00-05:00");
        assertEquals(morning, Reminders.deliveryAt(due, wake, "America/New_York", new Settings()));
    }
}
