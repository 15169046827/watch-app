package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class CalendarWindowTest {
    private final ZoneId zone = ZoneId.of("UTC");
    private final LocalDate day = LocalDate.parse("2026-10-01");

    private Instance item(String start, String end) {
        Instance item = new Instance();
        item.id = start;
        item.kind = Kind.REMINDER;
        item.zone = "UTC";
        item.start = Instant.parse(start).toEpochMilli();
        item.end = Instant.parse(end).toEpochMilli();
        return item;
    }

    @Test
    public void midnightEndIsNotAnExtraDayButCrossMidnightIsSplit() {
        Instance ends = item("2026-09-30T23:00:00Z", "2026-10-01T00:00:00Z");
        Instance crosses = item("2026-09-30T23:00:00Z", "2026-10-01T02:00:00Z");
        List<CalendarWindow.Slot> slots = CalendarWindow.slots(Arrays.asList(ends, crosses), day, zone);
        assertEquals(1, slots.size());
        assertEquals(0, slots.get(0).start);
        assertEquals(120, slots.get(0).end);
    }

    @Test
    public void overlappingGroupsUseSeparateColumnsAndReuseColumnsAfterEnd() {
        List<Instance> items = Arrays.asList(item("2026-10-01T09:00:00Z", "2026-10-01T11:00:00Z"),
                item("2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z"),
                item("2026-10-01T11:00:00Z", "2026-10-01T13:00:00Z"),
                item("2026-10-01T14:00:00Z", "2026-10-01T15:00:00Z"));
        List<CalendarWindow.Slot> slots = CalendarWindow.slots(items, day, zone);
        assertEquals(2, slots.get(0).columns);
        assertEquals(1, slots.get(1).column);
        assertEquals(0, slots.get(2).column);
        assertEquals(1, slots.get(3).columns);
    }

    @Test
    public void latePointKeepsItsTrueStartAndHasATouchableHeight() {
        Instance point = item("2026-10-01T23:59:00Z", "2026-10-01T23:59:00Z");
        CalendarWindow.Slot slot = CalendarWindow.slots(Collections.singletonList(point), day, zone).get(0);
        assertEquals(1439, slot.start);
        assertEquals(45, slot.end - slot.start);
    }

    @Test
    public void dateOnlyTodoUsesChosenDayRatherThanNextMidnight() {
        Plan todo = plan();
        todo.kind = Kind.TODO;
        todo.count = 1;
        todo.dateOnly = true;
        todo.todoDue = "2026-09-30";
        Instance item = Kinds.todo(todo);
        List<Instance> result = CalendarWindow.read(Collections.singletonList(todo), Collections.singletonList(item),
                Collections.emptyList(), new Settings(), day.minusDays(1), day.minusDays(1), zone,
                Instant.parse("2026-10-01T10:00:00Z").toEpochMilli());
        assertEquals(1, result.size());
        assertTrue(CalendarWindow.onDay(result, day, zone).isEmpty());
    }

    private Plan plan() {
        Plan plan = new Plan();
        plan.title = "合成日历";
        plan.anchor = day.minusDays(10).toString();
        plan.zone = "UTC";
        plan.rule = Rule.DAYS;
        return plan;
    }

    @Test
    public void browsingDoesNotInventPastInstancesAndPreservesSavedHistory() {
        Plan plan = plan();
        long now = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli();
        List<Instance> absent = CalendarWindow.read(Collections.singletonList(plan), Collections.emptyList(),
                Collections.emptyList(), new Settings(), day.minusDays(7), day.minusDays(1), zone, now);
        assertTrue(absent.isEmpty());
        Instance saved = Recurrence.generate(plan, day.minusDays(1), day.minusDays(1)).get(0);
        String before = Wire.JSON.toJson(saved);
        List<Instance> result = CalendarWindow.read(Collections.singletonList(plan), Collections.singletonList(saved),
                Collections.emptyList(), new Settings(), day.minusDays(7), day, zone, now);
        assertEquals(1, result.size());
        assertEquals(before, Wire.JSON.toJson(result.get(0)));
    }

    @Test
    public void longFutureEventIsVisibleInLaterMonthWithoutWritingIt() {
        Plan plan = plan();
        plan.anchor = "2026-10-03";
        plan.count = 1;
        plan.durationMinutes = 40 * 1440;
        List<Instance> result = CalendarWindow.read(Collections.singletonList(plan), Collections.emptyList(),
                Collections.emptyList(), new Settings(), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-05"),
                zone, Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
        assertEquals(1, result.size());
        assertEquals("2026-10-03", result.get(0).date);
    }
}
