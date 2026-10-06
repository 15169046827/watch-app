package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public class CalendarEditsTest {

    private Plan plan() {
        Plan p = new Plan();
        p.id = "p";
        p.title = "合成测试";
        p.anchor = "2026-09-01";
        p.rule = Rule.DAYS;
        return p;
    }

    private List<Instance> generate(Plan p, String first, String last) {
        return Recurrence.generate(p, LocalDate.parse(first), LocalDate.parse(last));
    }

    @Test
    public void verifiedYearsAndUnknownYear() {
        Plan p = plan();
        assertEquals(false, WorkCalendar.official(p, LocalDate.parse("2025-10-08")));
        assertEquals(true, WorkCalendar.official(p, LocalDate.parse("2025-10-11")));
        assertEquals(false, WorkCalendar.official(p, LocalDate.parse("2026-02-23")));
        assertEquals(true, WorkCalendar.official(p, LocalDate.parse("2026-09-20")));
        assertNull(WorkCalendar.official(p, LocalDate.parse("2027-01-01")));
    }

    @Test
    public void companyWinsButRequiresConfirmation() {
        Plan p = plan();
        p.rule = Rule.WORKDAYS;
        p.company.put("2026-10-01", true);
        assertTrue(generate(p, "2026-10-01", "2026-10-01").get(0).disputed);
        p.confirmed.put("2026-10-01", false);
        assertTrue(generate(p, "2026-10-01", "2026-10-01").isEmpty());
    }

    @Test
    public void localCalendarImportIsStrict() {
        assertEquals(2, WorkCalendar.preview("2027-01-01=REST\n2027-01-02=WORK").size());
        assertThrows(IllegalArgumentException.class, () -> WorkCalendar.preview("2027-01-01=REST\n2027-01-01=WORK"));
        assertThrows(RuntimeException.class, () -> WorkCalendar.preview("2027-02-30=WORK"));
    }

    @Test
    public void futureSplitKeepsOriginalIdsAndEarlierRule() {
        Plan p = plan();
        Plan draft = Wire.copy(p, Plan.class);
        draft.startMinute = 600;
        Plan result = PlanEdits.from(p, draft, LocalDate.parse("2026-09-20"), Instant.parse("2026-09-17T00:00:00Z")
                .toEpochMilli());
        List<Instance> is = generate(result, "2026-09-19", "2026-09-20");
        assertEquals(2, is.size());
        assertEquals("p:2026-09-20", is.get(1).id);
        assertEquals(6, Instant.ofEpochMilli(is.get(0).start).atZone(ZoneId.of(p.zone)).getHour());
        assertEquals(10, Instant.ofEpochMilli(is.get(1).start).atZone(ZoneId.of(p.zone)).getHour());
    }

    @Test
    public void secondSplitAndGlobalDefaults() {
        Plan p = plan();
        Plan draft = Wire.copy(p, Plan.class);
        draft.startMinute = 600;
        long now = Instant.parse("2026-09-17T00:00:00Z").toEpochMilli();
        Plan first = PlanEdits.from(p, draft, LocalDate.parse("2026-09-20"), now);
        Plan d2 = Wire.copy(first, Plan.class);
        d2.startMinute = 700;
        Plan second = PlanEdits.from(first, d2, LocalDate.parse("2026-09-25"), now);
        assertEquals(3, generate(second, "2026-09-24", "2026-09-26").size());
        second.inheritReminders = true;
        Settings settings = new Settings();
        settings.reminderTargets = 2;
        assertEquals(2, PlanEdits.withDefaults(second, settings).reminderTargets);
        assertEquals(3, second.reminderTargets);
    }

    @Test
    public void pastSplitRejectedAndDeletedSeriesStaysDeleted() {
        Plan p = plan();
        long now = Instant.parse("2026-09-17T00:00:00Z").toEpochMilli();
        assertThrows(IllegalArgumentException.class, () -> PlanEdits.from(p, p, LocalDate.parse("2026-09-16"), now));
        Plan result = PlanEdits.from(p, p, LocalDate.parse("2026-09-20"), now);
        result.deleted = true;
        assertTrue(generate(result, "2026-09-17", "2026-09-30").isEmpty());
    }
}
