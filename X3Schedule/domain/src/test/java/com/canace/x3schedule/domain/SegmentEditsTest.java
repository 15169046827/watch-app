package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.ExceptionDay;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SegmentEditsTest {
    private static final long NOW = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli();

    private Plan split() {
        Plan plan = new Plan();
        plan.id = "synthetic";
        plan.title = "合成计划";
        plan.anchor = "2026-09-01";
        plan.rule = Rule.DAYS;
        Plan draft = Wire.copy(plan, Plan.class);
        draft.startMinute = 600;
        return PlanEdits.from(plan, draft, LocalDate.parse("2026-10-15"), NOW);
    }

    private List<Instance> day(Plan plan, String date) {
        return Recurrence.generate(plan, LocalDate.parse(date), LocalDate.parse(date));
    }

    private ExceptionDay override(int minute, boolean skip) {
        ExceptionDay exception = new ExceptionDay();
        exception.minute = minute;
        exception.duration = 30;
        exception.skip = skip;
        return exception;
    }

    @Test
    public void singleEditAppliesToRetainedFutureSegmentWithoutMutatingPreviewSource() {
        Plan original = split();
        String before = Wire.JSON.toJson(original);
        Plan changed = PlanEdits.exception(original, LocalDate.parse("2026-10-01"), override(720, false), NOW);
        Instance instance = day(changed, "2026-10-01").get(0);
        assertEquals("synthetic:2026-10-01", instance.id);
        assertEquals(12, Instant.ofEpochMilli(instance.start).atZone(ZoneId.of(instance.zone)).getHour());
        assertEquals(30 * 60000L, instance.end - instance.start);
        assertEquals(before, Wire.JSON.toJson(original));
        assertTrue(changed.exceptions.isEmpty());
        assertTrue(changed.previous.get(0).exceptions.containsKey("2026-10-01"));
    }

    @Test
    public void cancelOnlySelectedOccurrenceAndKeepLaterRule() {
        Plan changed = PlanEdits.exception(split(), LocalDate.parse("2026-10-01"), override(720, true), NOW);
        assertTrue(day(changed, "2026-10-01").isEmpty());
        assertEquals(1, day(changed, "2026-10-02").size());
        assertEquals(10, Instant.ofEpochMilli(day(changed, "2026-10-15").get(0).start)
                .atZone(ZoneId.of(changed.zone)).getHour());
    }

    @Test
    public void splitBoundaryBelongsToNewSegment() {
        Plan changed = PlanEdits.exception(split(), LocalDate.parse("2026-10-15"), override(780, false), NOW);
        assertTrue(changed.previous.get(0).exceptions.isEmpty());
        assertTrue(changed.exceptions.containsKey("2026-10-15"));
        assertEquals(13, Instant.ofEpochMilli(day(changed, "2026-10-15").get(0).start)
                .atZone(ZoneId.of(changed.zone)).getHour());
    }

    @Test
    public void companyConflictAndConfirmationUseRetainedSegment() {
        LocalDate date = LocalDate.parse("2026-10-01");
        Plan company = PlanEdits.roster(split(), date, PlanEdits.RosterSource.COMPANY, true, NOW);
        assertTrue(day(company, date.toString()).get(0).disputed);
        assertEquals(Collections.singleton("2026-10-01"), WorkCalendar.disputedDates(company,
                LocalDate.parse("2026-09-22")));
        Plan confirmed = PlanEdits.roster(company, date, PlanEdits.RosterSource.CONFIRMED, false, NOW);
        assertTrue(day(confirmed, date.toString()).isEmpty());
        assertTrue(WorkCalendar.disputedDates(confirmed, LocalDate.parse("2026-09-22")).isEmpty());
        assertFalse(company.previous.get(0).confirmed.containsKey(date.toString()));
    }

    @Test
    public void officialEditDoesNotOverrideCompanyDecision() {
        LocalDate date = LocalDate.parse("2026-10-01");
        Plan company = PlanEdits.roster(split(), date, PlanEdits.RosterSource.COMPANY, true, NOW);
        Plan updated = PlanEdits.roster(company, date, PlanEdits.RosterSource.OFFICIAL, false, NOW);
        assertEquals(1, day(updated, date.toString()).size());
        assertTrue(day(updated, date.toString()).get(0).disputed);
    }

    @Test
    public void historicalOutOfRangeAndDeletedEditsAreRejected() {
        Plan plan = split();
        assertThrows(IllegalArgumentException.class, () -> PlanEdits.exception(plan,
                LocalDate.parse("2026-09-21"), override(720, false), NOW));
        plan.until = "2026-10-31";
        assertThrows(IllegalArgumentException.class, () -> PlanEdits.roster(plan,
                LocalDate.parse("2026-11-01"), PlanEdits.RosterSource.COMPANY, true, NOW));
        plan.deleted = true;
        assertThrows(IllegalArgumentException.class, () -> PlanEdits.exception(plan,
                LocalDate.parse("2026-10-01"), override(720, false), NOW));
    }

    @Test
    public void sameDayStartedInstanceRemainsFrozenAtReconciliation() {
        Plan plan = split();
        String date = "2026-09-22";
        List<Instance> old = day(plan, date);
        assertThrows(IllegalArgumentException.class,
                () -> PlanEdits.exception(plan, LocalDate.parse(date), override(720, false), NOW));
        List<Instance> merged = Recurrence.reconcile(old, day(plan, date), Collections.emptySet(), NOW);
        assertEquals(old.get(0).start, merged.get(0).start);
    }
}
