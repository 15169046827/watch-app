package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.ExceptionDay;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Models.State;
import com.canace.x3schedule.domain.Models.Suggestion;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class DomainTest {

    private Plan plan() {
        Plan p = new Plan();
        p.id = "p";
        p.title = "合成测试";
        p.anchor = "2024-01-01";
        return p;
    }

    private List<Instance> gen(Plan p, String a, String b) {
        return Recurrence.generate(p, LocalDate.parse(a), LocalDate.parse(b));
    }

    private Event event(Instance i, Action action, long clock) {
        Event e = new Event();
        e.instanceId = i.id;
        e.deviceId = "phone-test";
        e.logical = clock;
        e.action = action;
        e.confirmedAt = i.end + 60000;
        e.actualAt = e.confirmedAt;
        return e;
    }

    @Test
    public void weeksAnchorAndInterval() {
        Plan p = plan();
        p.interval = 2;
        p.weekdays = 1 | 4 | 16;
        assertEquals(6, gen(p, "2024-01-01", "2024-01-28").size());
    }

    @Test
    public void leapAndCountNotResetByQuery() {
        Plan p = plan();
        p.anchor = "2024-02-28";
        p.rule = Rule.DAYS;
        p.count = 3;
        assertEquals("2024-02-29", gen(p, "2024-02-29", "2024-03-10").get(0).date);
        assertEquals(2, gen(p, "2024-02-29", "2024-03-10").size());
    }

    @Test
    public void fifthWeekdaySkipsMissingMonths() {
        Plan p = plan();
        p.rule = Rule.MONTH_NTH;
        p.nth = 5;
        p.weekday = 1;
        assertEquals(1, gen(p, "2024-01-01", "2024-02-29").size());
    }

    @Test
    public void alternatingAcrossYear() {
        Plan p = plan();
        p.anchor = "2023-12-25";
        p.rule = Rule.ALTERNATING;
        assertTrue(Recurrence.base(p, LocalDate.parse("2023-12-30")));
        assertFalse(Recurrence.base(p, LocalDate.parse("2024-01-06")));
    }

    @Test
    public void rotation() {
        Plan p = plan();
        p.rule = Rule.ROTATION;
        p.rotation = "1111110";
        assertEquals(12, gen(p, "2024-01-01", "2024-01-14").size());
    }

    @Test
    public void companyWinsAndConfirmationOverrides() {
        Plan p = plan();
        p.rule = Rule.WORKDAYS;
        p.official.put("2024-01-01", false);
        p.company.put("2024-01-01", true);
        assertTrue(gen(p, "2024-01-01", "2024-01-01").get(0).disputed);
        p.confirmed.put("2024-01-01", false);
        assertTrue(gen(p, "2024-01-01", "2024-01-01").isEmpty());
    }

    @Test
    public void exceptionMaintainsId() {
        Plan p = plan();
        String id = gen(p, "2024-01-01", "2024-01-01").get(0).id;
        ExceptionDay x = new ExceptionDay();
        x.minute = 600;
        x.duration = 30;
        p.exceptions.put(p.anchor, x);
        p.version++;
        assertEquals(id, gen(p, p.anchor, p.anchor).get(0).id);
        x.skip = true;
        assertTrue(gen(p, p.anchor, p.anchor).isEmpty());
    }

    @Test
    public void dstGapAndCrossMidnight() {
        Plan p = plan();
        p.zone = "America/New_York";
        p.anchor = "2024-03-10";
        p.rule = Rule.DAYS;
        p.startMinute = 150;
        Instance i = gen(p, p.anchor, p.anchor).get(0);
        assertEquals(3, Instant.ofEpochMilli(i.start).atZone(ZoneId.of(p.zone)).getHour());
        assertEquals(3600000, i.end - i.start);
        p.startMinute = 1430;
        p.durationMinutes = 40;
        i = gen(p, p.anchor, p.anchor).get(0);
        assertEquals(11, Instant.ofEpochMilli(i.end).atZone(ZoneId.of(p.zone)).getDayOfMonth());
    }

    @Test
    public void historicalAndRecordedInstancesFreeze() {
        Plan p = plan();
        List<Instance> old = gen(p, "2024-01-01", "2024-01-03");
        p.startMinute += 60;
        p.version++;
        List<Instance> fresh = gen(p, "2024-01-01", "2024-01-03");
        List<Instance> r = Recurrence.reconcile(old, fresh, Collections.singleton(old.get(2).id), old.get(0).start);
        assertEquals(old.get(0).start, r.get(0).start);
        assertEquals(fresh.get(1).start, r.get(1).start);
        assertEquals(old.get(2).start, r.get(2).start);
    }

    @Test
    public void removalLeavesTombstone() {
        Plan p = plan();
        List<Instance> old = gen(p, p.anchor, p.anchor);
        List<Instance> r = Recurrence.reconcile(old, Collections.emptyList(), Collections.emptySet(), old.get(0)
                .start - 1);
        assertTrue(r.get(0).cancelled);
        assertFalse(old.get(0).cancelled);
    }

    @Test
    public void overlapIncludesPointButNotAdjacent() {
        Plan p = plan();
        Instance a = gen(p, p.anchor, p.anchor).get(0);
        Instance b = Wire.copy(a, Instance.class);
        b.id = "other";
        b.start = a.end;
        b.end = b.start + 1;
        assertFalse(Recurrence.overlaps(a, b));
        b.start = a.start;
        b.end = b.start;
        assertTrue(Recurrence.overlaps(a, b));
    }

    @Test
    public void pendingOverdueAndBackfill() {
        Instance i = gen(plan(), "2024-01-01", "2024-01-01").get(0);
        assertEquals(State.PENDING, Execution.state(i, null, i.end));
        assertEquals(State.OVERDUE, Execution.state(i, null, i.end + 86400000));
        Event e = event(i, Action.COMPLETE, 1);
        e.actualAt = null;
        e.completedEarlier = true;
        e.validate();
        assertEquals(State.COMPLETED_UNKNOWN, Execution.state(i, e, i.end + 999999999));
    }

    @Test
    public void mergeIsIdempotentAndOrderIndependent() {
        Instance i = gen(plan(), "2024-01-01", "2024-01-01").get(0);
        Event a = event(i, Action.START, 1);
        Event b = event(i, Action.COMPLETE, 2);
        assertEquals(2, Wire.mergeEvents(Arrays.asList(a, b), Arrays.asList(b, a)).size());
        assertEquals(b, Execution.winner(Arrays.asList(b, a), i.id));
        a.correction = true;
        assertEquals(a, Execution.winner(Arrays.asList(b, a), i.id));
    }

    @Test
    public void stableIdCollisionRejected() {
        Instance i = gen(plan(), "2024-01-01", "2024-01-01").get(0);
        Event a = event(i, Action.START, 1);
        Event b = Wire.copy(a, Event.class);
        b.logical = 2;
        assertThrows(IllegalArgumentException.class, () -> Wire.mergeEvents(Collections.singleton(a), Collections
                .singleton(b)));
    }

    @Test
    public void quotaAndReportsRecompute() {
        Plan p = plan();
        List<Instance> is = gen(p, "2024-01-01", "2024-01-03");
        List<Event> es = new ArrayList<>();
        es.add(event(is.get(0), Action.LEAVE, 1));
        es.add(event(is.get(1), Action.LEAVE, 2));
        Settings s = new Settings();
        Set<String> valid = Execution.validLeaves(is, es, Collections.singletonMap(p.id, p), s);
        assertEquals(1, valid.size());
        Execution.Report r = Execution.report(is, es, valid, 0, Long.MAX_VALUE, is.get(2).end + 86400000, null, null);
        assertEquals(1, r.excessLeave);
        assertEquals(1, r.overdue);
        es.add(event(is.get(2), Action.COMPLETE, 3));
        r = Execution.report(is, es, valid, 0, Long.MAX_VALUE, is.get(2).end + 86400000, null, null);
        assertEquals(.5, r.executionRate(), .001);
        assertEquals(1.0 / 3, r.fulfillmentRate(), .001);
    }

    @Test
    public void ackBindsBodyAndMessage() {
        Wire.Snapshot s = new Wire.Snapshot();
        s.source = "phone";
        s.deviceId = "phone-test";
        s.plans.add(plan());
        byte[] body = Wire.encode(s);
        Wire.Ack a = Wire.ack(s, body);
        assertTrue(Wire.accepts(a, s, body));
        s.id = "changed";
        assertFalse(Wire.accepts(a, s, body));
    }

    @Test
    public void tombstoneAndAuthority() {
        Plan p = plan();
        Plan q = Wire.copy(p, Plan.class);
        q.version = 2;
        q.deleted = true;
        assertTrue(Wire.mergePlan(p, q, "phone").deleted);
        assertTrue(Wire.mergePlan(q, p, "phone").deleted);
        assertThrows(IllegalArgumentException.class, () -> Wire.mergePlan(p, q, "watch"));
    }

    @Test
    public void wireVersionAndLimits() {
        Wire.Snapshot s = new Wire.Snapshot();
        s.source = "watch";
        s.deviceId = "watch-test";
        s.plans.add(plan());
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(s));
        s.plans.clear();
        s.protocol = 99;
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(s));
        assertThrows(IllegalArgumentException.class, () -> Wire.decode(new byte[Wire.MAX_BYTES + 1]));
    }

    @Test
    public void naturalLanguageOnlyDraft() {
        Plan p = NaturalLanguage.preview("每周一三五早上 6:30 到 7:30 晨跑", LocalDate.parse("2024-01-01"));
        assertEquals(21, p.weekdays);
        assertEquals(60, p.durationMinutes);
        assertThrows(IllegalArgumentException.class, () -> NaturalLanguage.preview("随便帮我安排", LocalDate.now()));
    }

    @Test
    public void naturalLanguageTitleDoesNotChangeMorningTime() {
        Plan draft = NaturalLanguage.preview("  每周一早上 6:30 到 7:30 准备下午会议材料  ", LocalDate.now());
        assertEquals(390, draft.startMinute);
        assertEquals(60, draft.durationMinutes);
        assertEquals("准备下午会议材料", draft.title);
    }

    @Test
    public void naturalLanguageTitleDoesNotRejectTwentyFourHourTime() {
        Plan draft = NaturalLanguage.preview("每周一 13:00 到 14:00 整理晚上要读的书", LocalDate.now());
        assertEquals(780, draft.startMinute);
        Plan afternoon = NaturalLanguage.preview("每周一下午 6:30 到 7:30 整理早上笔记", LocalDate.now());
        assertEquals(1110, afternoon.startMinute);
    }

    @Test
    public void suggestionsRequireEvidenceAndNeverApply() {
        assertNull(Execution.suggest("p", 5, 2, "late", "a", "b", "2024-01-01"));
        assertFalse(Execution.suggest("p", 5, 3, "late", "a", "b", "2024-01-01").formal);
        Suggestion s = Execution.suggest("p", 6, 4, "late", "a", "b", "2024-01-01");
        assertTrue(s.formal);
        assertFalse(s.accepted);
    }

    @Test
    public void icsRoundTripUnicodeEscapesAndDuplicateIdentity() {
        Plan p = plan();
        p.title = "晨跑,拉伸;笔记\\结束";
        String text = Ics.export(gen(p, p.anchor, p.anchor));
        List<Plan> a = Ics.preview(text);
        List<Plan> b = Ics.preview(text);
        assertEquals(p.title, a.get(0).title);
        assertEquals(a.get(0).id, b.get(0).id);
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(text.replace("END:VEVENT",
                "RRULE:FREQ=DAILY\r\nEND:VEVENT")));
    }

    @Test
    public void quietAcrossMidnight() {
        assertTrue(Execution.quiet(23 * 60, 22 * 60, 7 * 60));
        assertTrue(Execution.quiet(5 * 60, 22 * 60, 7 * 60));
        assertFalse(Execution.quiet(12 * 60, 22 * 60, 7 * 60));
    }

    @Test
    public void offlineExecutionKeepsOriginalSnapshotAfterPhoneEdit() {
        Instance original = gen(plan(), "2024-01-01", "2024-01-01").get(0);
        Event e = event(original, Action.COMPLETE, 1);
        e.snapshot = Wire.copy(original, Instance.class);
        e.validate();
        Instance changed = Wire.copy(original, Instance.class);
        changed.start += 600000;
        changed.end += 600000;
        changed.cancelled = true;
        Instance frozen = Execution.frozen(changed, Collections.singleton(e));
        assertEquals(original.start, frozen.start);
        assertFalse(frozen.cancelled);
        assertEquals(State.LATE, Execution.state(frozen, e, e.confirmedAt));
    }
}
