package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.LocalDate;
import org.junit.Test;

public class CapacityTest {
    private Wire.Snapshot snapshot() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        return snapshot;
    }

    @Test
    public void streamingMeasureMatchesActualUtf8IncludingEscapesAndSurrogates() {
        Wire.Snapshot snapshot = snapshot();
        Plan plan = new Plan();
        plan.title = "合成😀 <>& \"引号\" \\ 换行\n";
        snapshot.plans.add(plan);
        Capacity.Usage usage = Capacity.measure(snapshot);
        assertEquals(Wire.encode(snapshot).length, usage.bytes);
        assertEquals(1, usage.plans);
        assertEquals(0, usage.events);
        assertFalse(usage.nearLimit());
        assertFalse(usage.exceeded());
    }

    @Test
    public void measuringDoesNotChangeSnapshotOrPendingRevision() {
        Wire.Snapshot snapshot = snapshot();
        snapshot.revision = 37;
        String before = Wire.JSON.toJson(snapshot);
        Capacity.measure(snapshot);
        assertEquals(before, Wire.JSON.toJson(snapshot));
    }

    @Test
    public void exactLimitsAreAllowedButOneByteOrItemBeyondIsBlocked() {
        Capacity.Usage limit = new Capacity.Usage(Wire.MAX_BYTES, 1000, Wire.MAX_ITEMS, Wire.MAX_ITEMS);
        assertFalse(limit.exceeded());
        assertTrue(limit.nearLimit());
        assertTrue(new Capacity.Usage(Wire.MAX_BYTES + 1L, 0, 0, 0).exceeded());
        assertTrue(new Capacity.Usage(0, 1001, 0, 0).exceeded());
        assertTrue(new Capacity.Usage(0, 0, Wire.MAX_ITEMS + 1, 0).exceeded());
        assertTrue(new Capacity.Usage(0, 0, 0, Wire.MAX_ITEMS + 1).exceeded());
    }

    @Test
    public void approachingAnyLimitWarnsAtEightyPercent() {
        assertFalse(new Capacity.Usage(0, 799, 9599, 9599).nearLimit());
        assertTrue(new Capacity.Usage(0, 800, 0, 0).nearLimit());
        assertTrue(new Capacity.Usage(0, 0, 9600, 0).nearLimit());
        assertTrue(new Capacity.Usage(0, 0, 0, 9600).nearLimit());
        long below = Wire.MAX_BYTES * 4L / 5;
        assertFalse(new Capacity.Usage(below, 0, 0, 0).nearLimit());
        assertTrue(new Capacity.Usage(below + 1, 0, 0, 0).nearLimit());
    }

    @Test
    public void realisticLongHistoryExceedsBytesBeforeItemLimitWithoutBeingDiscarded() {
        Wire.Snapshot snapshot = snapshot();
        Plan plan = new Plan();
        plan.title = "合成容量测试";
        plan.anchor = "2026-01-01";
        plan.rule = Rule.DAYS;
        for (int index = 0; index < 30; index++) {
            plan.steps.add("测".repeat(80));
        }
        snapshot.plans.add(plan);
        LocalDate first = LocalDate.parse(plan.anchor);
        snapshot.instances.addAll(Recurrence.generate(plan, first, first.plusDays(319)));
        Capacity.Usage usage = Capacity.measure(snapshot);
        assertEquals(320, usage.instances);
        assertTrue(usage.bytes > Wire.MAX_BYTES);
        assertTrue(usage.exceeded());
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(snapshot));
        assertEquals(320, snapshot.instances.size());
        assertEquals(1, snapshot.plans.size());
    }
}
