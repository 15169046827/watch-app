package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.ExceptionDay;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.Test;

public class CalendarEditingTest {
    private Plan plan(String date) {
        Plan plan = new Plan();
        plan.title = "合成日历";
        plan.anchor = date;
        plan.zone = "UTC";
        plan.kind = Kind.REMINDER;
        plan.rule = Rule.DAYS;
        plan.startMinute = 600;
        return plan;
    }

    @Test
    public void explicitEndDateAllowsMultiDayAndRejectsEarlierEndAtomically() {
        Plan plan = plan("2026-10-01");
        LocalDateTime start = LocalDateTime.parse("2026-10-01T23:45");
        LocalDateTime end = LocalDateTime.parse("2026-10-03T08:15");
        CalendarEditing.times(plan, start, end, true);
        assertEquals(1950, plan.durationMinutes);
        assertEquals(end, CalendarEditing.end(plan));
        String before = Wire.JSON.toJson(plan);
        assertThrows(IllegalArgumentException.class, () -> CalendarEditing.times(plan, start,
                start.minusMinutes(1), true));
        assertEquals(before, Wire.JSON.toJson(plan));
        CalendarEditing.times(plan, start, end, false);
        assertEquals(0, plan.durationMinutes);
    }

    @Test
    public void durationFollowsElapsedTimeThroughDst() {
        Plan plan = plan("2026-03-08");
        plan.zone = "America/New_York";
        CalendarEditing.times(plan, LocalDateTime.parse("2026-03-08T01:30"),
                LocalDateTime.parse("2026-03-08T03:30"), true);
        assertEquals(60, plan.durationMinutes);
        assertEquals("2026-03-08T03:30", CalendarEditing.end(plan).toString());
        assertThrows(IllegalArgumentException.class, () -> CalendarEditing.times(plan,
                LocalDateTime.parse("2026-03-08T02:30"), LocalDateTime.parse("2026-03-08T04:30"), true));
    }

    @Test
    public void allDayEndIsInclusiveAndPreservesDstBoundaries() {
        Plan plan = plan("2026-03-07");
        plan.dateOnly = true;
        plan.zone = "America/New_York";
        CalendarEditing.times(plan, LocalDateTime.parse("2026-03-07T00:00"),
                LocalDateTime.parse("2026-03-09T00:00"), true);
        assertEquals(3, plan.daySpan);
        Models.Instance item = Recurrence.generate(plan, LocalDate.parse(plan.anchor),
                LocalDate.parse(plan.anchor)).get(0);
        Kinds.validateInstance(item);
        assertEquals(71, (item.end - item.start) / 3600000);
    }

    @Test
    public void monthAndYearRulesSkipMissingDates() {
        Plan monthly = plan("2026-01-31");
        monthly.rule = Rule.MONTH_DAY;
        List<Models.Instance> months = Recurrence.generate(monthly, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-04-30"));
        assertEquals(2, months.size());
        assertEquals("2026-03-31", months.get(1).date);
        Plan yearly = plan("2024-02-29");
        yearly.rule = Rule.YEARLY;
        assertEquals(2, Recurrence.generate(yearly, LocalDate.parse("2024-01-01"),
                LocalDate.parse("2028-12-31")).size());
        assertTrue(CalendarEditing.repeat(yearly).contains("2月29日"));
    }

    @Test
    public void movedOccurrenceKeepsIdentityAndAppearsInTargetWindow() {
        Plan plan = plan("2026-10-01");
        plan.count = 1;
        ExceptionDay moved = new ExceptionDay();
        moved.scheduledDate = "2026-10-03";
        moved.minute = 900;
        moved.duration = 60;
        moved.title = "合成改名单次";
        plan.exceptions.put(plan.anchor, moved);
        Models.Instance item = Recurrence.generate(plan, LocalDate.parse("2026-10-03"),
                LocalDate.parse("2026-10-03")).get(0);
        assertEquals(plan.id + ":2026-10-01", item.id);
        assertEquals(moved.title, item.title);
        assertEquals(0, Recurrence.generate(plan, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01")).size());
        moved.scheduledDate = "2026-09-30";
        assertEquals(1, Recurrence.generate(plan, LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-30")).size());
    }

    @Test
    public void deletingFollowingOccurrencesRetainsEarlierSegmentsWithoutMutatingSource() {
        Plan plan = plan("2026-10-01");
        String before = Wire.JSON.toJson(plan);
        Plan removed = PlanEdits.removeFrom(plan, LocalDate.parse("2026-10-04"),
                Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
        assertEquals(before, Wire.JSON.toJson(plan));
        assertFalse(removed.deleted);
        assertEquals("2026-10-03", removed.until);
        assertEquals(3, Recurrence.generate(removed, LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-10")).size());
    }
    @Test
    public void oneOffCanMoveEarlierWithoutDisappearingBehindItsOldEffectiveDate() {
        Plan original = plan("2026-10-10");
        original.count = 1;
        Plan draft = Wire.copy(original, Plan.class);
        draft.anchor = "2026-10-05";
        Plan changed = PlanEdits.from(original, draft, LocalDate.parse("2026-10-10"),
                Instant.parse("2026-10-02T00:00:00Z").toEpochMilli());
        List<Models.Instance> result = Recurrence.generate(changed, LocalDate.parse("2026-10-02"),
                LocalDate.parse("2026-10-12"));
        assertEquals(1, result.size());
        assertEquals("2026-10-05", result.get(0).date);
        assertEquals(original.id, result.get(0).planId);
        assertEquals("2026-10-10", original.anchor);
    }

    @Test
    public void followingEditUsesSelectedOptionsWithoutKeepingAnArtificialSplitEnd() {
        long now = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
        Plan original = plan("2026-10-02");
        original.until = "2026-10-20";
        Plan later = Wire.copy(original, Plan.class);
        later.startMinute = 900;
        later.title = "后续分段";
        Plan segmented = PlanEdits.from(original, later, LocalDate.parse("2026-10-10"), now);
        Plan draft = PlanEdits.followingDraft(segmented, LocalDate.parse("2026-10-05"));
        assertEquals(600, draft.startMinute);
        assertEquals(original.title, draft.title);
        assertEquals("2026-10-20", draft.until);
        draft.title = "从这次起调整";
        Plan changed = PlanEdits.from(segmented, draft, LocalDate.parse("2026-10-05"), now);
        List<Models.Instance> result = Recurrence.generate(changed, LocalDate.parse("2026-10-02"),
                LocalDate.parse("2026-10-25"));
        assertEquals(19, result.size());
        assertEquals(original.title, result.get(0).title);
        assertEquals(draft.title, result.get(3).title);
        assertEquals(draft.title, result.get(18).title);
        assertEquals("2026-10-20", result.get(18).date);
        assertEquals(900, segmented.startMinute);
    }
}
