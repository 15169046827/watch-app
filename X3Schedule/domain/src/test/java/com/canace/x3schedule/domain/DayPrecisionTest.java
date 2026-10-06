package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Models.State;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import org.junit.Test;

public class DayPrecisionTest {
    private Plan plan(Kind kind, String day, String zone) {
        Plan plan = new Plan();
        plan.title = "合成按天日程";
        plan.kind = kind;
        plan.dateOnly = true;
        plan.anchor = day;
        plan.zone = zone;
        plan.rule = Rule.DAYS;
        plan.count = 1;
        plan.durationMinutes = 0;
        plan.todoDue = kind == Kind.TODO ? day : "";
        return plan;
    }

    private Instance instance(Plan plan) {
        LocalDate day = LocalDate.parse(plan.anchor);
        return Recurrence.generate(plan, day, day).get(0);
    }

    @Test
    public void allDayUsesCalendarBoundariesAcrossDst() {
        for (String day : new String[] { "2026-03-08", "2026-11-01" }) {
            Instance item = instance(plan(Kind.REMINDER, day, "America/New_York"));
            Kinds.validateInstance(item);
            assertEquals(day.endsWith("03-08") ? 23 : 25, (item.end - item.start) / 3600000);
            assertEquals(State.REMINDER_PAST, Kinds.state(item, null, item.end));
        }
    }

    @Test
    public void dateDeadlineStaysOpenUntilNextMidnightAndRemindsAtNine() {
        Instance item = instance(plan(Kind.TODO, "2026-10-01", "Asia/Shanghai"));
        Kinds.validateInstance(item);
        assertEquals(State.TODO_OPEN, Kinds.state(item, null, item.start - 1));
        assertEquals(State.TODO_DUE, Kinds.state(item, null, item.start));
        Settings settings = new Settings();
        item.reminderMinutes = 120;
        long expected = LocalDate.parse("2026-10-01").atTime(9, 0).atZone(ZoneId.of(item.zone))
                .toInstant().toEpochMilli();
        assertEquals(expected, Reminders.candidates(item, null, true, settings).get(0).at);
        settings.dateOnlyReminderMinute = 600;
        assertEquals(expected + 3600000, Reminders.candidates(item, null, true, settings).get(0).at);
        settings.quietStart = 540;
        settings.quietEnd = 660;
        assertEquals(expected + 7200000, Reminders.candidates(item, null, true, settings).get(0).at);
    }

    @Test
    public void invalidDayPrecisionAndBoundariesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.EXECUTION, "2026-10-01", "UTC").validate());
        Plan todo = plan(Kind.TODO, "2026-10-01", "UTC");
        todo.todoDue = "";
        assertThrows(IllegalArgumentException.class, todo::validate);
        Instance item = instance(plan(Kind.REMINDER, "2026-10-01", "UTC"));
        item.end--;
        assertThrows(IllegalArgumentException.class, () -> Kinds.validateInstance(item));
    }

    private Wire.Snapshot snapshot() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        return snapshot;
    }

    @Test
    public void backupAndWireRetainPrecisionAndConfiguredTime() throws Exception {
        Wire.Snapshot source = snapshot();
        Plan plan = plan(Kind.TODO, "2026-10-01", "UTC");
        source.plans.add(plan);
        source.instances.add(instance(plan));
        source.settings.dateOnlyReminderMinute = 765;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Backup.write(source, out);
        Wire.Snapshot restored = Backup.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(765, restored.settings.dateOnlyReminderMinute);
        assertTrue(restored.plans.get(0).dateOnly);
        assertTrue(Wire.decode(Wire.encode(source)).instances.get(0).dateOnly);
        assertThrows(IllegalArgumentException.class, () -> Ics.export(source.instances));
    }

    @Test
    public void schemaThreeIsBackupOnlyAndCannotSmuggleDayPrecision() throws Exception {
        Wire.Snapshot old = snapshot();
        old.schema = 3;
        byte[] bytes = Wire.JSON.toJson(old).getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> Wire.decode(bytes));
        assertEquals(4, Wire.decodeBackup(bytes).schema);
        assertEquals(540, Wire.decodeBackup(bytes).settings.dateOnlyReminderMinute);
        old.plans.add(plan(Kind.REMINDER, "2026-10-01", "UTC"));
        assertThrows(IllegalArgumentException.class,
                () -> Wire.decodeBackup(Wire.JSON.toJson(old).getBytes(StandardCharsets.UTF_8)));
        assertFalse(Wire.JSON.fromJson("{}", Plan.class).dateOnly);
    }

    @Test
    public void legacyArchiveMigratesOnlyForBackupAndRejectsMixedVersions() throws Exception {
        byte[] old = archive(3, 3);
        assertEquals(4, Backup.read(new ByteArrayInputStream(old)).schema);
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotArchive.readArchive(new ByteArrayInputStream(old)));
        byte[] mixed = archive(3, 4);
        assertThrows(IllegalArgumentException.class, () -> Backup.read(new ByteArrayInputStream(mixed)));
    }

    private byte[] archive(int first, int second) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write("X3SCHEDULE-BACKUP/1\n".getBytes(StandardCharsets.US_ASCII));
        Wire.Snapshot page = snapshot();
        for (int version : new int[] { first, second }) {
            page.schema = version;
            byte[] body = Wire.JSON.toJson(page).getBytes(StandardCharsets.UTF_8);
            out.writeInt(body.length);
            out.write(body);
        }
        out.writeInt(0);
        out.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        return bytes.toByteArray();
    }

    @Test
    public void allDayDoesNotReplaceNextTimedAppointment() {
        Plan allDay = plan(Kind.REMINDER, "2026-10-01", "UTC");
        Instance item = instance(allDay);
        Agenda agenda = Agenda.read(Collections.singletonList(item), Collections.emptyList(),
                item.start + 1, ZoneId.of("UTC"));
        assertEquals(1, agenda.today.size());
        assertEquals(null, agenda.current);
        assertEquals(null, agenda.next);
    }
}
