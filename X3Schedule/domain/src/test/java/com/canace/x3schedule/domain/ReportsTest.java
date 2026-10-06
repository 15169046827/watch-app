package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ReportsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final long NOW = LocalDate.parse("2026-09-21").atStartOfDay(ZONE).toInstant().toEpochMilli();

    private Instance instance(String id, String date, String category) {
        Instance instance = new Instance();
        instance.id = id;
        instance.planId = "plan";
        instance.date = date;
        instance.zone = ZONE.getId();
        instance.title = "合成日程";
        instance.category = category;
        instance.start = LocalDate.parse(date).atTime(8, 0).atZone(ZONE).toInstant().toEpochMilli();
        instance.end = instance.start + 3600000;
        return instance;
    }

    private Event event(Instance instance, Action action) {
        Event event = new Event();
        event.instanceId = instance.id;
        event.action = action;
        event.deviceId = "synthetic";
        event.logical = 1;
        event.confirmedAt = instance.end;
        event.actualAt = instance.end;
        return event;
    }

    private Reports.Range month() {
        return Reports.range(Reports.Period.MONTH, LocalDate.parse("2026-09-21"), ZONE);
    }

    @Test
    public void weekIncludesCrossYearSundayAndMondayBoundaries() {
        Reports.Range range = Reports.range(Reports.Period.WEEK, LocalDate.parse("2027-01-03"), ZONE);
        assertEquals(LocalDate.parse("2026-12-28"), range.first);
        assertEquals(LocalDate.parse("2027-01-03"), range.last);
        assertEquals(7 * 86400000L, range.until - range.from);
    }

    @Test
    public void leapMonthAndYearUseCalendarBoundaries() {
        Reports.Range month = Reports.range(Reports.Period.MONTH, LocalDate.parse("2024-02-15"), ZONE);
        Reports.Range year = Reports.range(Reports.Period.YEAR, LocalDate.parse("2024-02-15"), ZONE);
        assertEquals(LocalDate.parse("2024-02-29"), month.last);
        assertEquals(366 * 86400000L, year.until - year.from);
    }

    @Test
    public void daylightSavingDayDoesNotAssumeTwentyFourHours() {
        Reports.Range range = Reports.range(Reports.Period.DAY, LocalDate.parse("2024-03-10"), ZoneId
                .of("America/New_York"));
        assertEquals(23 * 3600000L, range.until - range.from);
    }

    @Test
    public void invalidAndOverflowingDateRangesAreRejectedBeforeAggregation() {
        assertThrows(IllegalArgumentException.class, () -> new Reports.Range(LocalDate.of(2026, 9, 2), LocalDate
                .of(2026, 9, 1), ZONE));
        assertThrows(IllegalArgumentException.class, () -> new Reports.Range(LocalDate.of(2026, 9, 2), LocalDate
                .MAX, ZONE));
    }

    @Test
    public void everyExecutionOutcomeHasAnExplicitReportBucket() {
        List<Instance> instances = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            instances.add(instance("i" + index, "2026-09-19", "运动"));
        }
        Event onTime = event(instances.get(0), Action.COMPLETE);
        Event late = event(instances.get(1), Action.COMPLETE);
        late.actualAt += 60000;
        late.confirmedAt = late.actualAt;
        Event unknown = event(instances.get(2), Action.COMPLETE);
        unknown.actualAt = null;
        unknown.completedEarlier = true;
        events.addAll(Arrays.asList(onTime, late, unknown, event(instances.get(3), Action.LEAVE), event(instances
                .get(4), Action.LEAVE), event(instances.get(5), Action.INCOMPLETE)));
        instances.get(7).start = NOW - 3600000;
        instances.get(7).end = NOW + 3600000;
        Execution.Report report = Reports.summarize(instances, events, Collections.singleton("i3"), month(), NOW,
                null).total;
        assertEquals(8, report.planned);
        assertEquals(3, report.completed);
        assertEquals(1, report.onTime);
        assertEquals(1, report.late);
        assertEquals(1, report.completedUnknown);
        assertEquals(1, report.leave);
        assertEquals(1, report.excessLeave);
        assertEquals(1, report.running);
        assertEquals(2, report.incompleteIncludingOverdue());
        assertEquals(3.0 / 7, report.executionRate(), .0001);
        assertEquals(3.0 / 8, report.fulfillmentRate(), .0001);
        assertEquals(1.0 / 7, report.onTimeRate(), .0001);
        assertEquals(180, report.completedMinutes);
        assertEquals(540, report.plannedMinutes);
        assertEquals(1.0 / 3, report.plannedDurationRate(), .0001);
    }

    @Test
    public void pendingAndOverdueBoundaryRecomputesAfterCorrection() {
        Instance instance = instance("i", "2026-09-19", "运动");
        List<Instance> instances = Collections.singletonList(instance);
        Reports.Summary pending = Reports.summarize(instances, Collections.emptyList(), Collections.emptySet(),
                month(), instance.end + 86400000 - 1, null);
        assertEquals(1, pending.total.pending);
        Reports.Summary overdue = Reports.summarize(instances, Collections.emptyList(), Collections.emptySet(),
                month(), instance.end + 86400000, null);
        assertEquals(1, overdue.total.incompleteIncludingOverdue());
        Event incomplete = event(instance, Action.INCOMPLETE);
        incomplete.confirmedAt = NOW;
        Event corrected = event(instance, Action.COMPLETE);
        corrected.correction = true;
        Reports.Summary restored = Reports.summarize(instances, Arrays.asList(incomplete, corrected), Collections
                .emptySet(), month(), NOW, null);
        assertEquals(1, restored.total.onTime);
        assertEquals(0, restored.total.incompleteIncludingOverdue());
    }

    @Test
    public void filtersDoNotResetGlobalLeaveQuota() {
        Instance first = instance("a", "2026-09-14", "工作");
        Instance second = instance("b", "2026-09-15", "运动");
        List<Instance> instances = Arrays.asList(first, second);
        List<Event> events = Arrays.asList(event(first, Action.LEAVE), event(second, Action.LEAVE));
        Plan plan = new Plan();
        plan.id = "plan";
        Set<String> leaves = Execution.validLeaves(instances, events, Collections.singletonMap(plan.id, plan),
                new Settings());
        Reports.Range onlySecond = Reports.range(Reports.Period.DAY, LocalDate.parse(second.date), ZONE);
        Execution.Report report = Reports.summarize(instances, events, leaves, onlySecond, NOW, "运动").total;
        assertEquals(1, report.excessLeave);
        assertEquals(0, report.leave);
        assertEquals(0, report.executionRate(), .0001);
    }

    @Test
    public void historicalCategoriesSurvivePlanRenameAndDeletion() {
        Instance old = instance("old", "2026-09-14", "旧分类");
        Instance current = instance("current", "2026-09-15", "新分类");
        current.title = "新名称";
        Reports.Summary report = Reports.summarize(Arrays.asList(current, old), Collections.emptyList(), Collections
                .emptySet(), month(), NOW, null);
        assertEquals(2, report.plans.size());
        assertEquals(2, report.total.planned);
        assertEquals(1, report.categories.get("旧分类").planned);
        assertEquals(1, report.categories.get("新分类").planned);
        assertTrue(report.plans.values().stream().anyMatch(group -> "新名称".equals(group.title)));
        assertEquals("旧分类", old.category);
    }

    @Test
    public void futureCancelledAndExclusiveEndAreNotCounted() {
        Instance future = instance("future", "2026-09-22", "运动");
        Instance cancelled = instance("cancelled", "2026-09-19", "运动");
        cancelled.cancelled = true;
        Instance excluded = instance("excluded", "2026-10-01", "运动");
        Reports.Summary report = Reports.summarize(Arrays.asList(future, cancelled, excluded), Collections
                .emptyList(), Collections.emptySet(), month(), NOW, null);
        assertEquals(0, report.total.planned);
        assertNull(report.total.executionRate());
        assertTrue(report.categories.isEmpty());
    }

    @Test
    public void zeroDurationCompletionCountsWithoutInventingMinutes() {
        Instance point = instance("point", "2026-09-19", "运动");
        point.end = point.start;
        Event completed = event(point, Action.COMPLETE);
        Execution.Report report = Reports.summarize(Collections.singletonList(point), Collections
                .singletonList(completed), Collections.emptySet(), month(), NOW, null).total;
        assertEquals(1, report.completed);
        assertEquals(1, report.onTimeRate(), .0001);
        assertNull(report.plannedDurationRate());
    }

    @Test
    public void allLeaveDoesNotFabricateFullExecutionRate() {
        Instance instance = instance("leave", "2026-09-19", "运动");
        Execution.Report report = Reports.summarize(Collections.singletonList(instance), Collections
                .singletonList(event(instance, Action.LEAVE)), Collections.singleton(instance.id), month(), NOW,
                null).total;
        assertNull(report.executionRate());
        assertNull(report.onTimeRate());
        assertEquals(0, report.fulfillmentRate(), .0001);
    }

    @Test
    public void indexedMergeHasSamePrecedenceRegardlessOfInputOrder() {
        Instance instance = instance("i", "2026-09-19", "运动");
        Event first = event(instance, Action.START);
        Event last = event(instance, Action.COMPLETE);
        last.confirmedAt++;
        Map<String, Event> forward = Execution.winners(Arrays.asList(first, last));
        Map<String, Event> reverse = Execution.winners(Arrays.asList(last, first));
        assertEquals(last, forward.get(instance.id));
        assertEquals(forward, reverse);
        assertFalse(forward.isEmpty());
    }
}
