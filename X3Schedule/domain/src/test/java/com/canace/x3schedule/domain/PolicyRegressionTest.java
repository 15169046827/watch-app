package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class PolicyRegressionTest {

    @Test
    public void lastExplicitActionBeatsUnrelatedHigherLogicalClock() {
        Event a = new Event();
        Event b = new Event();
        a.instanceId = b.instanceId = "i";
        a.deviceId = "phone";
        b.deviceId = "watch";
        a.logical = 900;
        b.logical = 3;
        a.confirmedAt = 100;
        b.confirmedAt = 200;
        assertSame(b, Execution.winner(Arrays.asList(a, b), "i"));
        a.correction = true;
        assertSame(a, Execution.winner(Arrays.asList(b, a), "i"));
    }

    @Test
    public void globalOffSuppressesEvenSinglePlanOverride() {
        Plan p = new Plan();
        p.title = "合成测试";
        p.anchor = "2026-09-17";
        p.rule = Rule.DAYS;
        p.reminderTargets = 3;
        Instance i = Recurrence.generate(p, LocalDate.parse(p.anchor), LocalDate.parse(p.anchor)).get(0);
        Settings settings = new Settings();
        settings.phoneRemindersEnabled = false;
        assertTrue(Reminders.candidates(i, null, true, settings).isEmpty());
        assertFalse(Reminders.candidates(i, null, false, settings).isEmpty());
    }

    @Test
    public void splitCalendarImportAppliesToPreservedFutureSegment() {
        Plan p = new Plan();
        p.title = "合成测试";
        p.anchor = "2026-09-17";
        p.rule = Rule.WORKDAYS;
        Plan changed = PlanEdits.from(p, p, LocalDate.parse("2026-10-15"), Instant.parse("2026-09-17T00:00:00Z")
                .toEpochMilli());
        WorkCalendar.apply(changed, Collections.singletonMap("2026-10-01", true), true);
        assertEquals(1, Recurrence.generate(changed, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))
                .size());
    }
}
