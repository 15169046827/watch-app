package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class AgendaTest {
    private final long now = Instant.parse("2026-09-30T04:00:00Z").toEpochMilli();
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    private Instance item(String id, Kind kind, long start, long end) {
        Instance item = new Instance();
        item.id = id;
        item.kind = kind;
        item.start = start;
        item.end = end;
        return item;
    }

    @Test
    public void reminderNeverBecomesPendingAndUntimedTodoNeverBecomesCurrent() {
        Instance reminder = item("reminder", Kind.REMINDER, now - 1000, now + 1000);
        Instance todo = item("todo", Kind.TODO, 0, 0);
        Instance past = item("past", Kind.REMINDER, now - 90000, now - 80000);
        Agenda result = Agenda.read(Arrays.asList(todo, reminder, past), Collections.emptyList(), now, zone);
        assertEquals(0, result.pending);
        assertEquals(1, result.todos);
        assertSame(reminder, result.current);
        assertEquals(2, result.today.size());
    }

    @Test
    public void cancelledAndCompletedItemsDoNotHideNextOpenSchedule() {
        Instance cancelled = item("cancelled", Kind.EXECUTION, now + 1000, now + 2000);
        cancelled.cancelled = true;
        Instance done = item("done", Kind.EXECUTION, now + 2000, now + 3000);
        Event completed = new Event();
        completed.instanceId = done.id;
        completed.action = Action.COMPLETE;
        completed.actualAt = now;
        Instance next = item("next", Kind.REMINDER, now + 5000, now + 6000);
        Agenda result = Agenda.read(Arrays.asList(next, done, cancelled),
                Collections.singletonList(completed), now, zone);
        assertSame(next, result.next);
        assertNull(result.current);
        assertEquals(Arrays.asList(done, next), result.today);
    }

    @Test
    public void pendingIncludesOlderRecordsAndContinueCanRemainCurrent() {
        Instance overdue = item("overdue", Kind.EXECUTION, now - 180000000, now - 170000000);
        Instance extended = item("extended", Kind.EXECUTION, now - 10000, now - 1000);
        Event event = new Event();
        event.instanceId = extended.id;
        event.action = Action.CONTINUE;
        event.confirmedAt = now - 1000;
        event.continueMinutes = 5;
        Agenda result = Agenda.read(Arrays.asList(overdue, extended), Collections.singletonList(event), now, zone);
        assertEquals(1, result.pending);
        assertSame(extended, result.current);
    }

    @Test
    public void todayUsesCalendarBoundsAcrossDaylightSavingAndIncludesOvernightItems() {
        ZoneId local = ZoneId.of("America/New_York");
        long instant = Instant.parse("2026-03-08T12:00:00Z").toEpochMilli();
        Instance overnight = item("overnight", Kind.EXECUTION,
                Instant.parse("2026-03-08T04:00:00Z").toEpochMilli(),
                Instant.parse("2026-03-08T06:00:00Z").toEpochMilli());
        Instance tomorrow = item("tomorrow", Kind.REMINDER,
                Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(),
                Instant.parse("2026-03-09T04:10:00Z").toEpochMilli());
        Agenda result = Agenda.read(Arrays.asList(tomorrow, overnight), Collections.emptyList(), instant, local);
        assertEquals(Collections.singletonList(overnight), result.today);
    }
}
