package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Models.State;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class KindsTest {
    private static final LocalDate DAY = LocalDate.parse("2026-09-27");
    private static final long NOW = Instant.parse("2026-09-27T00:00:00Z").toEpochMilli();

    private Plan plan(Kind kind) {
        Plan plan = new Plan();
        plan.id = kind.name();
        plan.title = "合成类型日程";
        plan.anchor = DAY.toString();
        plan.kind = kind;
        plan.rule = Rule.DAYS;
        plan.startMinute = 600;
        if (kind == Kind.TODO) {
            plan.count = 1;
            plan.durationMinutes = 0;
        }
        return plan;
    }

    private Instance instance(Plan plan) {
        return Recurrence.generate(plan, DAY, DAY).get(0);
    }

    private Event event(Instance instance, Action action, long at) {
        Event event = new Event();
        event.instanceId = instance.id;
        event.snapshot = Wire.copy(instance, Instance.class);
        event.action = action;
        event.confirmedAt = at;
        event.actualAt = at;
        event.logical = at;
        event.deviceId = "synthetic";
        return event;
    }

    private Wire.Snapshot snapshot() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        return snapshot;
    }

    @Test
    public void oldJsonKeepsExecutionMeaning() {
        Plan plan = Wire.JSON.fromJson("{\"title\":\"合成旧计划\"}", Plan.class);
        Instance instance = Wire.JSON.fromJson("{}", Instance.class);
        assertEquals(Kind.EXECUTION, plan.kind);
        assertEquals(Kind.EXECUTION, instance.kind);
        assertTrue(instance.timed);
    }

    @Test
    public void reminderHasOneAlarmAndNeverNeedsCompletion() {
        Instance instance = instance(plan(Kind.REMINDER));
        assertEquals(1, Reminders.candidates(instance, null, true, new Settings()).size());
        assertEquals(State.REMINDER_UPCOMING, Execution.state(instance, null, instance.start - 1));
        assertEquals(State.REMINDER_PAST, Execution.state(instance, null, instance.end + 86400000L));
        assertThrows(IllegalArgumentException.class,
                () -> event(instance, Action.COMPLETE, NOW).validate());
        assertThrows(IllegalArgumentException.class, () -> Kinds.validateAction(instance, Action.LEAVE));
    }

    @Test
    public void executionRetainsFourAlarmsAndPendingState() {
        Instance instance = instance(plan(Kind.EXECUTION));
        assertEquals(4, Reminders.candidates(instance, null, true, new Settings()).size());
        assertEquals(State.PENDING, Execution.state(instance, null, instance.end));
    }

    @Test
    public void undatedTodoSurvivesRollingWindowWithoutInventedTime() {
        Plan plan = plan(Kind.TODO);
        Instance first = instance(plan);
        Instance later = Recurrence.generate(plan, DAY.plusYears(2), DAY.plusYears(2).plusDays(180)).get(0);
        assertEquals(first.id, later.id);
        assertFalse(later.timed);
        assertEquals(0, later.start);
        assertTrue(Reminders.candidates(later, null, true, new Settings()).isEmpty());
        assertEquals(State.TODO_OPEN, Execution.state(later, null, NOW + 999 * 86400000L));
    }

    @Test
    public void datedTodoOnlyRemindsAtDeadlineAndDoesNotBecomeExecutionOverdue() {
        Plan plan = plan(Kind.TODO);
        plan.todoDue = "2026-10-01T13:00";
        Instance instance = instance(plan);
        assertEquals(instance.start, instance.end);
        assertEquals(1, Reminders.candidates(instance, null, true, new Settings()).size());
        assertEquals(State.TODO_OPEN, Execution.state(instance, null, instance.start - 1));
        assertEquals(State.TODO_DUE, Execution.state(instance, null, instance.start));
    }

    @Test
    public void todoCheckOffReopenAndCrossDeviceOrderingAreDeterministic() {
        Instance instance = instance(plan(Kind.TODO));
        Event complete = event(instance, Action.COMPLETE, NOW);
        Event reopen = event(instance, Action.REOPEN, NOW + 1000);
        reopen.deviceId = "watch";
        complete.validate();
        reopen.validate();
        assertEquals(State.TODO_DONE, Execution.state(instance, complete, NOW));
        assertTrue(Reminders.candidates(instance, complete, true, new Settings()).isEmpty());
        for (List<Event> order : Arrays.asList(Arrays.asList(complete, reopen), Arrays.asList(reopen, complete))) {
            assertEquals(State.TODO_OPEN, Execution.state(instance, Execution.winner(order, instance.id), NOW + 1000));
        }
    }

    @Test
    public void todoRejectsExecutionOnlyActionsAndCorrectionPriority() {
        Instance instance = instance(plan(Kind.TODO));
        for (Action action : Arrays.asList(Action.START, Action.CONTINUE, Action.LEAVE, Action.INCOMPLETE)) {
            assertThrows(IllegalArgumentException.class, () -> Kinds.validateAction(instance, action));
        }
        Event complete = event(instance, Action.COMPLETE, NOW);
        complete.correction = true;
        assertThrows(IllegalArgumentException.class, complete::validate);
        assertThrows(IllegalArgumentException.class,
                () -> Kinds.validateAction(instance(plan(Kind.EXECUTION)), Action.REOPEN));
    }

    @Test
    public void todoDeadlineEditsKeepIdentityAndCompletionWhileTakingNewMetadata() {
        Plan plan = plan(Kind.TODO);
        Instance old = instance(plan);
        Event complete = event(old, Action.COMPLETE, NOW);
        plan.todoDue = "2026-10-02T09:00";
        plan.title = "合成新名称";
        plan.version++;
        Instance fresh = instance(plan);
        Instance merged = Recurrence.reconcile(Collections.singletonList(old), Collections.singletonList(fresh),
                Collections.singleton(old.id), NOW).get(0);
        assertEquals(old.id, merged.id);
        assertEquals("合成新名称", merged.title);
        assertTrue(merged.timed);
        assertEquals(State.TODO_DONE, Execution.state(merged, complete, NOW));
        assertEquals(merged.start, Execution.frozen(merged, Collections.singletonList(complete)).start);
    }

    @Test
    public void removedTodoStaysCancelledDespiteOldOfflineCompletion() {
        Instance old = instance(plan(Kind.TODO));
        Event complete = event(old, Action.COMPLETE, NOW);
        Instance removed = Recurrence.reconcile(Collections.singletonList(old), Collections.emptyList(),
                Collections.singleton(old.id), NOW).get(0);
        assertTrue(removed.cancelled);
        assertTrue(Execution.frozen(removed, Collections.singletonList(complete)).cancelled);
        assertEquals(State.CANCELLED, Execution.state(removed, complete, NOW));
    }

    @Test
    public void typeSwitchPreservesHistoricalInstances() {
        Plan old = plan(Kind.EXECUTION);
        Plan draft = Wire.copy(old, Plan.class);
        draft.kind = Kind.REMINDER;
        Plan changed = PlanEdits.from(old, draft, DAY.plusDays(1), NOW);
        List<Instance> items = Recurrence.generate(changed, DAY, DAY.plusDays(1));
        assertEquals(Kind.EXECUTION, items.get(0).kind);
        assertEquals(Kind.REMINDER, items.get(1).kind);
    }

    @Test
    public void oldExecutionSnapshotSurvivesOfflineFutureTypeSwitch() {
        Instance old = instance(plan(Kind.EXECUTION));
        Event start = event(old, Action.START, NOW);
        Instance reminder = Wire.copy(old, Instance.class);
        reminder.kind = Kind.REMINDER;
        assertEquals(Kind.EXECUTION, Execution.frozen(reminder, Collections.singletonList(start)).kind);
    }

    @Test
    public void reportsAndLeaveQuotaIgnoreBothNonExecutionTypes() {
        Instance execution = instance(plan(Kind.EXECUTION));
        Instance reminder = instance(plan(Kind.REMINDER));
        Instance todo = instance(plan(Kind.TODO));
        Execution.Report report = Execution.report(Arrays.asList(execution, reminder, todo), Collections.emptyList(),
                Collections.emptySet(), 0, NOW + 10 * 86400000L, NOW + 86400000L, null, null);
        assertEquals(1, report.planned);
        Event badLegacyLeave = event(todo, Action.LEAVE, NOW);
        assertTrue(Execution.validLeaves(Collections.singletonList(todo), Collections.singletonList(badLegacyLeave),
                Collections.singletonMap(todo.planId, plan(Kind.TODO)), new Settings()).isEmpty());
    }

    @Test
    public void reminderAndTodoNeverProduceOptimizationAdvice() {
        Plan plan = plan(Kind.TODO);
        Instance instance = instance(plan);
        assertTrue(Optimizer.analyze(Collections.singletonList(plan), Collections.singletonList(instance),
                Collections.singletonList(event(instance, Action.COMPLETE, NOW)), NOW).isEmpty());
        plan.kind = Kind.REMINDER;
        assertTrue(Optimizer.analyze(Collections.singletonList(plan), Collections.emptyList(),
                Collections.emptyList(), NOW).isEmpty());
    }

    @Test
    public void newBackupRoundTripsAllTypesAndUndatedCompletion() {
        Wire.Snapshot snapshot = snapshot();
        for (Kind kind : Kind.values()) {
            Plan plan = plan(kind);
            snapshot.plans.add(plan);
            snapshot.instances.add(instance(plan));
        }
        snapshot.events.add(event(snapshot.instances.get(2), Action.COMPLETE, NOW));
        Wire.Snapshot decoded = Wire.decode(Wire.encode(snapshot));
        assertEquals(Wire.JSON.toJson(snapshot), Wire.JSON.toJson(decoded));
        assertEquals(Kind.TODO, decoded.events.get(0).snapshot.kind);
    }

    @Test
    public void legacyBackupUpgradesButLegacyLiveSyncIsRejected() {
        Wire.Snapshot snapshot = snapshot();
        snapshot.plans.add(plan(Kind.EXECUTION));
        snapshot.instances.add(instance(snapshot.plans.get(0)));
        String json = Wire.JSON.toJson(snapshot).replace("\"protocol\":2", "\"protocol\":1")
                .replace("\"schema\":4", "\"schema\":2").replace("\"kind\":\"EXECUTION\",", "")
                .replace("\"timed\":true,", "").replace("\"todoDue\":\"\",", "");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Wire.Snapshot upgraded = Wire.decodeBackup(bytes);
        assertEquals(2, upgraded.protocol);
        assertEquals(4, upgraded.schema);
        assertEquals(Kind.EXECUTION, upgraded.instances.get(0).kind);
        assertThrows(IllegalArgumentException.class, () -> Wire.decode(bytes));
    }

    @Test
    public void legacyEnvelopeCannotSmuggleNewTypes() {
        Wire.Snapshot snapshot = snapshot();
        snapshot.plans.add(plan(Kind.TODO));
        snapshot.protocol = 1;
        snapshot.schema = 2;
        byte[] bytes = Wire.JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeBackup(bytes));
    }

    @Test
    public void invalidKindUntimedExecutionAndRepeatedTodoAreRejected() {
        Plan plan = plan(Kind.TODO);
        plan.count = 0;
        assertThrows(IllegalArgumentException.class, plan::validate);
        plan.count = 1;
        plan.todoDue = "2026-10-01T09:00:01";
        assertThrows(IllegalArgumentException.class, plan::validate);
        Wire.Snapshot snapshot = snapshot();
        Instance instance = instance(plan(Kind.EXECUTION));
        instance.timed = false;
        snapshot.instances.add(instance);
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(snapshot));
        plan.kind = null;
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    public void convertingTodoOrChangingItsIdentityIsRejected() {
        Plan todo = plan(Kind.TODO);
        Plan draft = Wire.copy(todo, Plan.class);
        draft.anchor = DAY.plusDays(1).toString();
        assertThrows(IllegalArgumentException.class, () -> Kinds.requireChange(todo, draft));
        assertThrows(IllegalArgumentException.class, () -> Kinds.requireChange(todo, plan(Kind.EXECUTION)));
    }

    @Test
    public void todoDeadlineChangeGetsNewReminderKey() {
        Plan todo = plan(Kind.TODO);
        todo.todoDue = "2026-10-01T09:00";
        String first = Reminders.candidates(instance(todo), null, true, new Settings()).get(0).key;
        todo.todoDue = "2026-10-02T09:00";
        String next = Reminders.candidates(instance(todo), null, true, new Settings()).get(0).key;
        assertFalse(first.equals(next));
    }

    @Test
    public void eventSnapshotCannotConvertExecutionIntoTodoOrEraseTodoIdentity() {
        Instance execution = instance(plan(Kind.EXECUTION));
        Instance todo = instance(plan(Kind.TODO));
        Event todoEvent = event(todo, Action.REOPEN, NOW);
        assertThrows(IllegalArgumentException.class, () -> Kinds.validateEventPurpose(execution, todoEvent));
        Event oldEvent = event(execution, Action.COMPLETE, NOW);
        assertThrows(IllegalArgumentException.class, () -> Kinds.validateEventPurpose(todo, oldEvent));
        oldEvent.snapshot = null;
        assertThrows(IllegalArgumentException.class, () -> Kinds.validateEventPurpose(todo, oldEvent));
        Kinds.validateEventPurpose(execution, oldEvent);
    }
}
