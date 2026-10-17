package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ProgressTest {
    private Instance item() {
        Plan plan = new Plan();
        plan.title = "合成学习";
        plan.anchor = "2026-10-17";
        plan.rule = Models.Rule.DAYS;
        plan.count = 1;
        plan.startMinute = 540;
        plan.durationMinutes = 60;
        plan.progress = new Progress.Options();
        plan.progress.perfect = "完成五章";
        plan.progress.basic = "完成两章";
        plan.progress.minimum = "打开书";
        plan.progress.recoveryMinute = 840;
        return Recurrence.generate(plan, LocalDate.parse(plan.anchor), LocalDate.parse(plan.anchor)).get(0);
    }

    private Event result(Instance item, Progress.Session session, Progress.Grade grade, boolean acted, int clock) {
        Event event = new Event();
        event.snapshot = Wire.copy(item, Instance.class);
        event.instanceId = item.id;
        event.deviceId = "synthetic";
        event.logical = clock;
        event.confirmedAt = item.end + clock * 1000L;
        event.action = Models.Action.PROGRESS;
        event.progress = new Progress.Entry();
        event.progress.op = Progress.Op.RESULT;
        event.progress.session = session;
        event.progress.grade = grade;
        event.progress.acted = acted;
        return event;
    }

    @Test
    public void scoringMatrixPreservesMinimumActionEvenAtFivePoints() {
        int[][] values = { { 0, 0, 0 }, { 30, 15, 5 }, { 60, 45, 30 }, { 100, 80, 60 } };
        for (Progress.Grade grade : Progress.Grade.values()) {
            for (int deviations = 0; deviations <= 2; deviations++) {
                assertEquals(values[grade.ordinal()][deviations], Progress.score(grade, deviations));
            }
        }
        assertEquals("● 最低行动", Progress.label(Progress.Grade.MINIMUM));
    }

    @Test
    public void originalAndScheduledMissesAreIndependentAndCumulative() {
        Instance item = item();
        List<Event> events = new ArrayList<>();
        events.add(result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1));
        events.add(result(item, Progress.Session.SCHEDULED, Progress.Grade.MINIMUM, false, 2));
        events.add(result(item, Progress.Session.MANUAL, Progress.Grade.PERFECT, true, 3));
        Progress.Summary summary = Progress.summarize(item, events);
        assertEquals(80, Progress.score(summary.grade, summary.deviations()));
        events.set(0, result(item, Progress.Session.ORIGINAL, Progress.Grade.NONE, false, 1));
        summary = Progress.summarize(item, events);
        assertEquals(60, Progress.score(summary.grade, summary.deviations()));
    }

    @Test
    public void equalGradeScheduledActionIsRecognizedAndDuplicateIdsAreIdempotent() {
        Instance item = item();
        Event first = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        Event second = result(item, Progress.Session.SCHEDULED, Progress.Grade.MINIMUM, true, 2);
        Progress.Summary summary = Progress.summarize(item, Arrays.asList(second, first, second));
        assertTrue(summary.scheduledActed);
        assertEquals(0, summary.scheduledUpgrades);
        assertEquals(0, summary.deviations());
    }

    @Test
    public void noFeedbackIsNotNoneAndNoResultsHaveNoScore() {
        Instance item = item();
        assertNull(Progress.summarize(item, Collections.emptyList()).grade);
        ProgressReport.Summary summary = ProgressReport.range(Collections.singleton(item),
                Collections.emptyList(), LocalDate.parse(item.taskDay), LocalDate.parse(item.taskDay), item.end + 1);
        assertNull(summary.score());
        assertEquals(1, summary.pending);
        assertEquals(-1, ProgressReport.band(summary.score()));
    }

    @Test
    public void taskDayUsesLocalBoundaryAcrossDstWithoutSubtractingElapsedHours() {
        long before = java.time.ZonedDateTime.parse("2026-11-01T03:30-05:00[America/New_York]")
                .toInstant().toEpochMilli();
        assertEquals(LocalDate.parse("2026-10-31"), Progress.day(before, "America/New_York", 240));
        Instance item = item();
        assertEquals(Progress.boundary(LocalDate.parse("2026-10-21"), item.zone, 240), Progress.expiry(item));
    }

    @Test
    public void binaryDefaultsRejectPartialStandardsAndForgedGrades() {
        Progress.Options options = new Progress.Options();
        options.validate();
        options.basic = "一半";
        assertThrows(IllegalArgumentException.class, options::validate);
        Instance item = item();
        item.progress.perfect = "";
        item.progress.basic = "";
        item.progress.minimum = "";
        Event invalid = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        assertThrows(IllegalArgumentException.class, invalid::validate);
    }

    @Test
    public void legacyBackupMigratesWithoutInventingGradeOrLiveCompatibility() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.deviceId = "synthetic";
        snapshot.source = "phone";
        snapshot.schema = 4;
        byte[] old = Wire.JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        assertEquals(5, Wire.decodeBackup(old).schema);
        assertThrows(IllegalArgumentException.class, () -> Wire.decode(old));
        Plan injected = new Plan();
        injected.title = "不可信的新规则";
        injected.progress = new Progress.Options();
        snapshot.plans.add(injected);
        assertThrows(IllegalArgumentException.class,
                () -> Wire.decodeBackup(Wire.JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void missedOriginalCannotBeCorrectedAndManualImprovementRetainsIt() {
        Instance item = item();
        Event none = result(item, Progress.Session.ORIGINAL, Progress.Grade.NONE, false, 1);
        Event perfect = result(item, Progress.Session.MANUAL, Progress.Grade.PERFECT, true, 2);
        none.validate();
        perfect.validate();
        Progress.Summary summary = Progress.summarize(item, Arrays.asList(none, perfect));
        assertEquals(80, Progress.score(summary.grade, summary.deviations()));
        assertThrows(IllegalArgumentException.class, () -> Progress.requireRecord(item,
                Collections.singleton(none), none.progress, item.end + 1));
    }

    @Test
    public void recoveryConsentUsesLongestContinuousRemainderAndStalePreviewIsRejected() {
        Instance owner = item();
        Instance other = Wire.copy(owner, Instance.class);
        other.id = "other:" + owner.date;
        other.planId = "other";
        other.start = owner.end + 4 * 3600000L;
        other.end = other.start + 3600000L;
        other.progress.allowYield = true;
        long start = other.start + 20 * 60000L;
        long end = other.end - 20 * 60000L;
        List<RecoveryPlanner.Impact> preview = RecoveryPlanner.preview(owner, start, end,
                Arrays.asList(owner, other), Collections.emptyList(), owner.end);
        assertEquals(1.0 / 3, preview.get(0).remaining, 0.0001);
        Progress.Entry entry = new Progress.Entry();
        entry.start = start;
        entry.end = end;
        entry.conflictKey = RecoveryPlanner.fingerprint(owner, start, end, preview);
        RecoveryPlanner.requireConsent(owner, entry, preview);
        other.end += 60000L;
        assertThrows(IllegalArgumentException.class, () -> RecoveryPlanner.requireConsent(owner, entry,
                RecoveryPlanner.preview(owner, start, end, Collections.singleton(other),
                        Collections.emptyList(), owner.end)));
    }

    @Test
    public void untimedTaskDoesNotAccumulateTimeDeviation() {
        Instance item = item();
        item.progress.untimed = true;
        item.progress.recoveryMinute = -1;
        Progress.bind(item, item.progress);
        Progress.Summary summary = Progress.summarize(item, Collections.singletonList(
                result(item, Progress.Session.ORIGINAL, Progress.Grade.NONE, false, 1)));
        assertFalse(summary.originalMiss);
    }

    @Test
    public void aggregateThresholdsUseUnroundedMean() {
        assertEquals(3, ProgressReport.band(79.999));
        assertEquals("79分", ProgressReport.display(79.999));
        assertEquals(0, ProgressReport.band(9.999));
        assertEquals(1, ProgressReport.band(10.0));
        assertEquals(4, ProgressReport.band(100.0));
    }

    @Test
    public void concurrentLeaveClaimsUseOneFrozenQuotaWithoutDeletingLoser() {
        Instance first = item();
        Instance second = Wire.copy(first, Instance.class);
        second.id = "another:" + first.date;
        second.planId = "another";
        Event a = result(first, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        a.progress.op = Progress.Op.LEAVE;
        a.progress.grade = null;
        Event b = result(second, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 2);
        b.progress.op = Progress.Op.LEAVE;
        b.progress.grade = null;
        List<Event> both = Arrays.asList(b, a);
        assertTrue(ProgressLeaves.accepted(both).contains(a.id));
        assertFalse(ProgressLeaves.accepted(both).contains(b.id));
        assertTrue(Progress.summaries(Arrays.asList(first, second), both).get(first.id).exempt());
        assertFalse(Progress.summaries(Arrays.asList(first, second), both).get(second.id).exempt());
    }

    @Test
    public void standbyAcknowledgementDoesNotFreezeFutureSchedule() {
        Instance item = item();
        Event ack = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        ack.confirmedAt = item.start - 60000;
        ack.progress.op = Progress.Op.ACK_CONFLICT;
        ack.progress.grade = null;
        Instance changed = Wire.copy(item, Instance.class);
        changed.start += 3600000;
        assertFalse(Progress.freezes(ack));
        assertEquals(changed.start, Execution.frozen(changed, Collections.singletonList(ack)).start);
    }

    @Test
    public void correctionReprojectsOriginalFactWithoutChangingAuditTrail() {
        Instance item = item();
        Event original = result(item, Progress.Session.ORIGINAL, Progress.Grade.PERFECT, true, 1);
        Event correction = correction(item, original, Progress.Grade.MINIMUM, 2);
        List<Event> events = Arrays.asList(correction, original);
        correction.validate();
        ProgressHistory.validate(events);
        assertEquals(Progress.Grade.MINIMUM, Progress.summarize(item, events).grade);
        assertEquals(Progress.Grade.PERFECT, original.progress.grade);
        assertEquals(1, ProgressHistory.correctableResults(item.id, events).size());
    }

    @Test
    public void confirmedNoneCannotBeErasedByCorrectionOrLaterPerfect() {
        Instance item = item();
        Event original = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        Event none = correction(item, original, Progress.Grade.NONE, 2);
        Event undo = correction(item, original, Progress.Grade.PERFECT, 3);
        assertThrows(IllegalArgumentException.class,
                () -> ProgressHistory.validate(Arrays.asList(original, none, undo)));
        Event manual = result(item, Progress.Session.MANUAL, Progress.Grade.PERFECT, true, 4);
        Progress.Summary state = Progress.summarize(item, Arrays.asList(original, none, manual));
        assertEquals(80, Progress.score(state.grade, state.deviations()));
        assertTrue(ProgressHistory.correctableResults(item.id, Arrays.asList(original, none)).isEmpty());
    }

    @Test
    public void correctionCannotCrossTaskOrReferenceMissingFact() {
        Instance item = item();
        Event original = result(item, Progress.Session.ORIGINAL, Progress.Grade.BASIC, true, 1);
        Event correction = correction(item, original, Progress.Grade.PERFECT, 2);
        assertThrows(IllegalArgumentException.class,
                () -> ProgressHistory.validate(Collections.singleton(correction)));
        correction.instanceId = "another";
        assertThrows(IllegalArgumentException.class,
                () -> ProgressHistory.validate(Arrays.asList(original, correction)));
    }

    @Test
    public void untimedReminderAtBoundaryStartsTodayAndCheckinAtBoundaryEndsTomorrow() {
        Instance item = item();
        item.progress.untimed = true;
        item.progress.recoveryMinute = -1;
        item.progress.remindMinute = 240;
        item.progress.checkinMinute = 240;
        Progress.bind(item, item.progress);
        assertEquals(Progress.boundary(LocalDate.parse(item.taskDay), item.zone, 240), item.start);
        assertEquals(item.taskDayEnd, item.end);
        Models.Settings settings = new Models.Settings();
        settings.quietStart = 0;
        settings.quietEnd = 0;
        List<Reminders.Due> reminders = ProgressReminders.candidates(item, Collections.emptyList(), true, settings);
        assertEquals(item.start, reminders.get(0).at);
        assertEquals(item.end, reminders.get(1).at);
    }

    @Test
    public void recoveryFingerprintDoesNotChangeOnlyBecauseClockPassedOtherStart() {
        Instance owner = item();
        Instance other = Wire.copy(owner, Instance.class);
        other.id = "other";
        other.start = owner.end + 3600000;
        other.end = other.start + 3600000;
        other.progress.allowYield = true;
        long start = other.start + 300000;
        long end = other.start + 600000;
        List<RecoveryPlanner.Impact> before = RecoveryPlanner.preview(owner, start, end,
                Collections.singleton(other), Collections.emptyList(), other.start - 1);
        List<RecoveryPlanner.Impact> after = RecoveryPlanner.preview(owner, start, end,
                Collections.singleton(other), Collections.emptyList(), other.start + 1);
        assertFalse(before.get(0).hard);
        assertTrue(after.get(0).hard);
        assertEquals(RecoveryPlanner.fingerprint(owner, start, end, before),
                RecoveryPlanner.fingerprint(owner, start, end, after));
    }

    @Test
    public void manualPerfectAfterUnsuccessfulRecoveryIsNotFormalPerfect() {
        Instance item = item();
        List<Event> events = Arrays.asList(
                result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1),
                result(item, Progress.Session.SCHEDULED, Progress.Grade.MINIMUM, true, 2),
                result(item, Progress.Session.MANUAL, Progress.Grade.PERFECT, true, 3));
        ProgressReport.Summary report = ProgressReport.range(Collections.singleton(item), events,
                LocalDate.parse(item.taskDay), LocalDate.parse(item.taskDay), item.end + 10000);
        assertEquals(1, report.perfect);
        assertEquals(0, report.recoveryPerfect);
        assertEquals(1, report.recoveryActed);
        assertEquals(1, report.manualActions);
    }

    private Event correction(Instance item, Event original, Progress.Grade grade, int clock) {
        Event event = result(item, original.progress.session, grade, grade != Progress.Grade.NONE, clock);
        event.correction = true;
        event.progress.op = Progress.Op.CORRECT;
        event.progress.replacesId = original.id;
        return event;
    }

    @Test
    public void parallelOfflineRecoveryBranchesConvergeWithoutDeletingFacts() {
        Instance item = item();
        Event original = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        Event phone = enable(item, 2, 0);
        Event watch = enable(item, 3, 600000);
        Progress.Summary first = Progress.summarize(item, Arrays.asList(original, phone, watch));
        Progress.Summary reverse = Progress.summarize(item, Arrays.asList(watch, phone, original));
        assertTrue(first.recoveryConflict);
        assertEquals(1, first.enabledCount);
        assertEquals(first.recoveryStart, reverse.recoveryStart);
        assertEquals(phone.progress.start, first.earliestRecoveryStart);
        Progress.Entry cancel = new Progress.Entry();
        cancel.op = Progress.Op.CANCEL;
        assertThrows(IllegalArgumentException.class, () -> Progress.requireRecord(item,
                Arrays.asList(original, phone, watch), cancel, phone.progress.start));
        Event feedback = result(item, Progress.Session.SCHEDULED, Progress.Grade.BASIC, true, 4);
        feedback.confirmedAt = watch.progress.end + 1;
        feedback.progress.recoveryParent = watch.id;
        Progress.Summary resolved = Progress.summarize(item, Arrays.asList(feedback, phone, watch, original));
        assertFalse(resolved.recoveryConflict);
        assertTrue(resolved.scheduledActed);
    }

    @Test
    public void acceptedRecoveryImpactDoesNotBecomeNewConflictAtItsStart() {
        Instance item = item();
        Event enabled = enable(item, 2, 0);
        Instance other = Wire.copy(item, Instance.class);
        other.id = "other";
        other.progress.allowYield = true;
        other.start = enabled.progress.start - 600000;
        other.end = enabled.progress.end + 3600000;
        List<Instance> items = Arrays.asList(item, other);
        List<RecoveryPlanner.Impact> preview = RecoveryPlanner.preview(item, enabled.progress.start,
                enabled.progress.end, items, Collections.emptyList(), item.end);
        enabled.progress.conflictKey = RecoveryPlanner.fingerprint(item, enabled.progress.start,
                enabled.progress.end, preview);
        assertFalse(RecoveryConflicts.scan(items, Collections.singleton(enabled), other.start + 1)
                .stream().anyMatch(c -> c.item.id.equals(item.id)));
        other.end += 60000;
        assertTrue(RecoveryConflicts.scan(items, Collections.singleton(enabled), other.start + 1)
                .stream().anyMatch(c -> c.item.id.equals(item.id)));
    }

    @Test
    public void feedbackExpiresExactlyAtFourthFollowingBoundary() {
        Instance item = item();
        Progress.Entry entry = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1).progress;
        Progress.requireRecord(item, Collections.emptyList(), entry, Progress.expiry(item) - 1);
        assertThrows(IllegalArgumentException.class,
                () -> Progress.requireRecord(item, Collections.emptyList(), entry, Progress.expiry(item)));
    }

    private Event enable(Instance item, int clock, long offset) {
        Event event = result(item, Progress.Session.SCHEDULED, Progress.Grade.MINIMUM, true, clock);
        event.progress.op = Progress.Op.ENABLE;
        event.progress.grade = null;
        event.progress.start = item.end + 4 * 3600000L + offset;
        event.progress.end = event.progress.start + 1800000;
        return event;
    }

    @Test
    public void actualAchievementCountsIncludeUnscoredTasksWithoutChangingWeightedDenominator() {
        Instance item = item();
        item.progress.scored = false;
        Event event = result(item, Progress.Session.ORIGINAL, Progress.Grade.PERFECT, true, 1);
        ProgressReport.Summary summary = ProgressReport.range(Collections.singleton(item), Collections.singleton(event),
                LocalDate.parse(item.taskDay), LocalDate.parse(item.taskDay), item.end + 1);
        assertEquals(1, summary.perfect);
        assertEquals(0, summary.results);
        assertNull(summary.score());
    }

    @Test
    public void startedTaskWithExplicitAchievementCanAppearBeforeItsFeedbackDeadline() {
        Instance item = item();
        Event event = result(item, Progress.Session.ORIGINAL, Progress.Grade.PERFECT, true, 1);
        event.confirmedAt = item.start + 1;
        ProgressReport.Summary summary = ProgressReport.range(Collections.singleton(item), Collections.singleton(event),
                LocalDate.parse(item.taskDay), LocalDate.parse(item.taskDay), item.start + 2);
        assertEquals(100.0, summary.score(), 0.0001);
        ProgressReport.Summary pending = ProgressReport.range(Collections.singleton(item), Collections.emptyList(),
                LocalDate.parse(item.taskDay), LocalDate.parse(item.taskDay), item.start + 2);
        assertEquals(0, pending.pending);
    }

    @Test
    public void recoveryRunningAndPendingRemainDiscoverableAfterOriginalFeedback() {
        Instance item = item();
        Event original = result(item, Progress.Session.ORIGINAL, Progress.Grade.MINIMUM, true, 1);
        Event enabled = enable(item, 2, 0);
        Progress.Summary state = Progress.summarize(item, Arrays.asList(original, enabled));
        assertEquals(Models.State.RUNNING, Progress.state(item, state, state.recoveryStart));
        assertEquals(Models.State.PENDING, Progress.state(item, state, state.recoveryEnd));
        assertEquals(1, Agenda.read(Collections.singletonList(item), Arrays.asList(original, enabled),
                state.recoveryEnd, java.time.ZoneId.of(item.zone)).pending);
        ProgressReport.Summary report = ProgressReport.range(Collections.singleton(item),
                Arrays.asList(original, enabled),
                LocalDate.parse(item.taskDay), LocalDate.parse(item.taskDay), state.recoveryEnd);
        assertEquals(1, report.pending);
        assertEquals(1, report.minimum);
        assertNull(report.score());
    }

    @Test
    public void fullBackupPreservesGradesRulesAndCorrectionAuditFacts() throws Exception {
        Instance item = item();
        Event original = result(item, Progress.Session.ORIGINAL, Progress.Grade.PERFECT, true, 1);
        Event correction = correction(item, original, Progress.Grade.MINIMUM, 2);
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        snapshot.instances.add(item);
        snapshot.events.add(original);
        snapshot.events.add(correction);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        Backup.write(snapshot, bytes);
        Wire.Snapshot restored = Backup.read(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(2, restored.events.size());
        assertEquals(item.progress.perfect, restored.instances.get(0).progress.perfect);
        assertEquals(Progress.Grade.MINIMUM, Progress.summarize(item, restored.events).grade);
        restored.events.removeIf(event -> event.id.equals(original.id));
        assertThrows(IllegalArgumentException.class, () -> Backup.validate(restored));
    }

    @Test
    public void untimedTaskNeverOccupiesFixedTimeInPreviewOrOverlapChecks() {
        Instance fixed = item();
        Instance untimed = Wire.copy(fixed, Instance.class);
        untimed.id = "untimed";
        untimed.planId = "untimed";
        untimed.progress.untimed = true;
        untimed.progress.recoveryMinute = -1;
        Progress.bind(untimed, untimed.progress);
        assertFalse(Recurrence.overlaps(fixed, untimed));
        OverlapIndex index = new OverlapIndex(Arrays.asList(fixed, untimed));
        assertEquals(0, index.count(fixed));
        assertEquals(0, index.count(untimed));
    }

    @Test
    public void scheduledResultRequiresRealCommitmentEvidenceInCompleteHistory() {
        Instance item = item();
        Event feedback = result(item, Progress.Session.SCHEDULED, Progress.Grade.BASIC, true, 3);
        assertThrows(IllegalArgumentException.class,
                () -> ProgressHistory.validate(Collections.singleton(feedback)));
        Event enabled = enable(item, 2, 0);
        assertThrows(IllegalArgumentException.class,
                () -> ProgressHistory.validate(Arrays.asList(enabled, feedback)));
        feedback.confirmedAt = enabled.progress.end;
        ProgressHistory.validate(Arrays.asList(feedback, enabled));
    }

    @Test
    public void binaryUnfinishedWithActionDoesNotInventAnOriginalMiss() {
        Instance item = item();
        item.progress.perfect = "";
        item.progress.basic = "";
        item.progress.minimum = "";
        Event unfinished = result(item, Progress.Session.ORIGINAL, Progress.Grade.NONE, true, 1);
        unfinished.validate();
        Progress.Summary partial = Progress.summarize(item, Collections.singleton(unfinished));
        assertFalse(partial.originalMiss);
        assertEquals("— 未完成", Progress.achievement(partial));
        Event perfect = result(item, Progress.Session.MANUAL, Progress.Grade.PERFECT, true, 2);
        Progress.Summary complete = Progress.summarize(item, Arrays.asList(unfinished, perfect));
        assertEquals(100, Progress.score(complete.grade, complete.deviations()));
        unfinished.progress.acted = false;
        Progress.Summary missed = Progress.summarize(item, Arrays.asList(unfinished, perfect));
        assertEquals(80, Progress.score(missed.grade, missed.deviations()));
    }
}
