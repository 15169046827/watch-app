package com.canace.x3schedule.shared;

import static org.junit.Assert.assertEquals;

import com.canace.x3schedule.domain.Kinds;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import java.time.ZoneId;
import org.junit.Test;

public final class CalendarLabelsTest {
    @Test
    public void deadlineFreeTodoPreviewDoesNotShowEpochTime() {
        Plan plan = new Plan();
        plan.kind = Kind.TODO;
        plan.todoDue = "";
        assertEquals("不设截止", CalendarScreen.label(Kinds.todo(plan), ZoneId.of("UTC")));
    }

    @Test
    public void dateDeadlineKeepsChosenDateAcrossDisplayZones() {
        Plan plan = new Plan();
        plan.kind = Kind.TODO;
        plan.zone = "Asia/Shanghai";
        plan.dateOnly = true;
        plan.todoDue = "2026-10-02";
        assertEquals("2026-10-02 · 当天结束时截止",
                CalendarScreen.label(Kinds.todo(plan), ZoneId.of("America/New_York")));
    }
}
