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
import com.canace.x3schedule.domain.Models.Settings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.Test;

public class ReminderScopeTest {
    private Plan legacy() {
        Plan plan = new Plan();
        plan.title = "合成旧提醒";
        plan.anchor = "2026-10-09";
        plan.inheritReminders = false;
        plan.reminderTargets = 2;
        return plan;
    }

    @Test
    public void watchNeverSchedulesAnyKindEvenWithLegacyMasterSwitch() {
        Plan plan = legacy();
        Instance instance = Recurrence.generate(plan, LocalDate.parse(plan.anchor),
                LocalDate.parse(plan.anchor)).get(0);
        Settings settings = new Settings();
        settings.watchRemindersEnabled = true;
        Event continued = new Event();
        continued.action = Action.CONTINUE;
        continued.continueMinutes = 15;
        for (Kind kind : Kind.values()) {
            instance.kind = kind;
            for (int targets = 0; targets <= 3; targets++) {
                instance.reminderTargets = targets;
                assertTrue(Reminders.candidates(instance, null, false, settings).isEmpty());
                assertTrue(Reminders.candidates(instance, continued, false, settings).isEmpty());
            }
        }
    }

    @Test
    public void phoneStillHonorsTargetsAndMasterSwitch() {
        Plan plan = legacy();
        Instance instance = Recurrence.generate(plan, LocalDate.parse(plan.anchor),
                LocalDate.parse(plan.anchor)).get(0);
        Settings settings = new Settings();
        for (int targets = 0; targets <= 3; targets++) {
            instance.reminderTargets = targets;
            assertEquals((targets & 1) == 0 ? 0 : 4,
                    Reminders.candidates(instance, null, true, settings).size());
        }
        settings.phoneRemindersEnabled = false;
        assertTrue(Reminders.candidates(instance, null, true, settings).isEmpty());
    }

    @Test
    public void newPendingPlansAreRejectedWithoutChangingDataOrEnablingPhone() {
        Plan plan = legacy();
        Settings settings = new Settings();
        settings.phoneRemindersEnabled = false;
        String before = Wire.JSON.toJson(plan);
        assertThrows(IllegalArgumentException.class, () -> ReminderScope.requireSave(plan, null, settings));
        assertEquals(before, Wire.JSON.toJson(plan));
        assertFalse(settings.phoneRemindersEnabled);
        plan.inheritReminders = true;
        settings.reminderTargets = 2;
        assertThrows(IllegalArgumentException.class, () -> ReminderScope.requireSave(plan, null, settings));
        settings.reminderTargets = 0;
        ReminderScope.requireSave(plan, null, settings);
    }

    @Test
    public void existingPendingPlansCanBeKeptOrExplicitlyResolved() {
        Plan prior = legacy();
        Plan draft = Wire.copy(prior, Plan.class);
        Settings settings = new Settings();
        ReminderScope.requireSave(draft, prior, settings);
        assertTrue(ReminderScope.pending(draft, settings));
        for (int target : new int[] { 0, 1 }) {
            draft.reminderTargets = target;
            ReminderScope.requireSave(draft, prior, settings);
            assertFalse(ReminderScope.pending(draft, settings));
        }
        draft.reminderTargets = 2;
        prior.reminderTargets = 1;
        assertThrows(IllegalArgumentException.class, () -> ReminderScope.requireSave(draft, prior, settings));
    }

    @Test
    public void backupAndWireKeepLegacyTargetsExplicitlyPending() throws IOException {
        Wire.Snapshot original = new Wire.Snapshot();
        original.source = "phone";
        original.deviceId = "synthetic";
        original.plans.add(legacy());
        original.settings.reminderTargets = 2;
        original.settings.watchRemindersEnabled = true;
        original.settings.phoneRemindersEnabled = false;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Backup.write(original, bytes);
        Wire.Snapshot restored = Backup.read(new ByteArrayInputStream(bytes.toByteArray()));
        Wire.Snapshot synchronizedData = Wire.decode(Wire.encode(original));
        for (Wire.Snapshot result : new Wire.Snapshot[] { restored, synchronizedData }) {
            assertEquals(Wire.JSON.toJson(original), Wire.JSON.toJson(result));
            assertTrue(ReminderScope.pending(result.plans.get(0), result.settings));
            assertFalse(result.settings.phoneRemindersEnabled);
        }
    }
}
