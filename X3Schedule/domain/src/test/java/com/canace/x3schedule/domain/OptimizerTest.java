package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class OptimizerTest {
    private static final long NOW = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
    private final Plan plan = plan("main");
    private final List<Instance> instances = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();

    private static Plan plan(String id) {
        Plan plan = new Plan();
        plan.id = id;
        plan.title = "合成计划";
        plan.anchor = "2026-09-01";
        plan.rule = Rule.DAYS;
        plan.startMinute = 600;
        return plan;
    }

    private Event event(Instance instance, Action action, long actual) {
        Event event = new Event();
        event.instanceId = instance.id;
        event.deviceId = "synthetic";
        event.logical = events.size() + 1;
        event.action = action;
        event.actualAt = actual;
        event.confirmedAt = actual;
        event.snapshot = Wire.copy(instance, Instance.class);
        events.add(event);
        return event;
    }

    private void sample(Plan definition, LocalDate date, int delay, int duration) {
        Instance instance = Recurrence.generate(definition, date, date).get(0);
        instances.add(instance);
        long start = instance.start + delay * 60000L;
        event(instance, Action.START, start);
        event(instance, Action.COMPLETE, start + duration * 60000L);
    }

    private void samples(int count, int late, int duration) {
        for (int i = 0; i < count; i++) {
            sample(plan, LocalDate.parse(plan.anchor).plusDays(i), i < late ? 10 : 0, duration);
        }
    }

    private List<Optimizer.Advice> analyze() {
        return Optimizer.analyze(Collections.singletonList(plan), instances, events, NOW);
    }

    private Optimizer.Advice find(String kind) {
        return analyze().stream().filter(a -> a.suggestion.kind.equals(kind)).findFirst().orElse(null);
    }

    @Test
    public void observationAndFormalThresholdsUseActualDenominator() {
        samples(5, 3, 60);
        assertFalse(find("START").suggestion.formal);
        sample(plan, LocalDate.parse("2026-09-06"), 0, 60);
        assertFalse(find("START").suggestion.formal);
        events.clear();
        instances.clear();
        samples(6, 4, 60);
        assertTrue(find("START").suggestion.formal);
        assertEquals(6, find("START").suggestion.samples);
        assertEquals(4, find("START").suggestion.matching);
    }

    @Test
    public void fewerThanThreeMatchesProduceNoObservation() {
        samples(6, 2, 60);
        assertTrue(analyze().isEmpty());
    }

    @Test
    public void lateStartDoesNotImplyInsufficientDuration() {
        samples(6, 6, 60);
        assertTrue(find("START").suggestion.formal);
        assertEquals(null, find("DURATION"));
        assertEquals("610", find("START").suggestion.proposedValue);
    }

    @Test
    public void durationUsesActualStartAndCompletion() {
        samples(6, 0, 77);
        assertEquals("80", find("DURATION").suggestion.proposedValue);
        assertEquals(null, find("START"));
    }

    @Test
    public void unknownCompletionIsOnlyConfirmationEvidence() {
        samples(6, 0, 60);
        for (Event event : events) {
            if (event.action == Action.COMPLETE) {
                event.completedEarlier = true;
                event.actualAt = null;
            }
        }
        assertTrue(find("CONFIRM").suggestion.formal);
        assertEquals(null, find("DURATION"));
        assertFalse(find("CONFIRM").adjustable);
    }

    @Test
    public void confirmationDelayDoesNotBecomeExecutionDelay() {
        samples(6, 0, 60);
        for (Event event : events) {
            if (event.action == Action.COMPLETE) {
                event.confirmedAt += 30 * 60000L;
            }
        }
        assertTrue(find("CONFIRM").suggestion.formal);
        assertEquals(null, find("DURATION"));
        assertEquals(null, find("START"));
    }

    @Test
    public void staleVersionsDeletedPlansAndExceptionsAreExcluded() {
        samples(6, 6, 60);
        plan.version++;
        assertTrue(analyze().isEmpty());
        plan.version--;
        plan.deleted = true;
        assertTrue(analyze().isEmpty());
        plan.deleted = false;
        plan.startMinute++;
        assertTrue(analyze().isEmpty());
    }

    @Test
    public void correctionWinsOverAnEarlierCompletion() {
        samples(6, 6, 60);
        for (Instance instance : instances) {
            Event correction = event(instance, Action.LEAVE, instance.end + 3600000L);
            correction.correction = true;
        }
        assertTrue(analyze().isEmpty());
    }

    @Test
    public void missingStartNeverFabricatesTimingEvidence() {
        samples(6, 6, 80);
        events.removeIf(e -> e.action == Action.START);
        assertEquals(null, find("START"));
        assertEquals(null, find("DURATION"));
    }

    @Test
    public void weekdayHasItsOwnSampleCount() {
        for (int i = 0; i < 3; i++) {
            sample(plan, LocalDate.parse("2026-09-01").plusWeeks(i), 10, 60);
        }
        Optimizer.Advice weekday = find("WEEKDAY");
        assertEquals(3, weekday.suggestion.samples);
        assertFalse(weekday.suggestion.formal);
        assertFalse(weekday.adjustable);
    }

    @Test
    public void precedingOverlapIsAnObservationNotAnAutomaticReschedule() {
        Plan prior = plan("prior");
        prior.startMinute = 570;
        for (int i = 0; i < 6; i++) {
            LocalDate date = LocalDate.parse(plan.anchor).plusDays(i);
            sample(prior, date, 0, 35);
            sample(plan, date, 10, 60);
        }
        assertTrue(find("OVERLAP").suggestion.formal);
        assertFalse(find("OVERLAP").adjustable);
    }

    @Test
    public void previewPreservesHistoryAndDoesNotMutatePlan() {
        samples(6, 6, 60);
        Optimizer.Advice advice = find("START");
        String original = Wire.JSON.toJson(plan);
        Plan draft = Optimizer.preview(plan, advice, NOW);
        assertEquals(original, Wire.JSON.toJson(plan));
        assertEquals("2026-09-26", draft.effectiveFrom);
        assertEquals(600, draft.previous.get(0).startMinute);
        assertEquals(610, draft.startMinute);
        assertFalse(advice.suggestion.accepted);
        LocalDate past = LocalDate.parse("2026-09-03");
        assertEquals(Recurrence.generate(plan, past, past).get(0).start,
                Recurrence.generate(draft, past, past).get(0).start);
    }

    @Test
    public void previewRejectsStaleObservationAndExpiredEffectiveDate() {
        samples(6, 6, 60);
        Optimizer.Advice advice = find("START");
        plan.version++;
        assertThrows(IllegalArgumentException.class, () -> Optimizer.preview(plan, advice, NOW));
        plan.version--;
        assertThrows(IllegalArgumentException.class, () -> Optimizer.preview(plan, advice, NOW + 86400000L));
        advice.suggestion.formal = false;
        assertThrows(IllegalArgumentException.class, () -> Optimizer.preview(plan, advice, NOW));
    }

    @Test
    public void endedPlanRemainsObservationAndCannotBeApplied() {
        samples(6, 6, 60);
        plan.until = "2026-09-20";
        assertFalse(find("START").adjustable);
        assertThrows(IllegalArgumentException.class, () -> Optimizer.preview(plan, find("START"), NOW));
    }

    @Test
    public void futureAndOldEvidenceCannotEnterTheAnalysis() {
        samples(6, 6, 60);
        assertTrue(Optimizer.analyze(Collections.singletonList(plan), instances, events,
                Instant.parse("2026-08-31T00:00:00Z").toEpochMilli()).isEmpty());
        assertTrue(Optimizer.analyze(Collections.singletonList(plan), instances, events,
                NOW + 100 * 86400000L).isEmpty());
    }

    @Test
    public void impossibleDurationAndCrossMidnightProposalStayUnapplied() {
        plan.startMinute = 1435;
        samples(6, 6, 60);
        assertFalse(find("START").adjustable);
        for (Event event : events) {
            if (event.action == Action.START) {
                event.actualAt += 120 * 60000L;
                event.confirmedAt = event.actualAt;
            }
        }
        assertEquals(null, find("START"));
        assertEquals(null, find("DURATION"));
    }

    @Test
    public void scheduledFutureSegmentIsNotOverwrittenBeforeItsEffectiveDate() {
        samples(6, 6, 60);
        plan.effectiveFrom = "2026-10-01";
        Optimizer.Advice advice = find("START");
        assertEquals("2026-10-01", advice.suggestion.futureFrom);
        assertEquals("2026-10-01", Optimizer.preview(plan, advice, NOW).effectiveFrom);
    }
}
