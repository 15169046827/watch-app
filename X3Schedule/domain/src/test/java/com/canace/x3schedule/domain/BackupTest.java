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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BackupTest {
    private Wire.Snapshot snapshot() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        snapshot.id = "backup-test";
        snapshot.revision = 27;
        return snapshot;
    }

    private byte[] encode(Wire.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Backup.write(snapshot, bytes);
        return bytes.toByteArray();
    }

    private Wire.Snapshot decode(byte[] bytes) throws IOException {
        return Backup.read(new ByteArrayInputStream(bytes));
    }

    private Wire.Snapshot history(int days, boolean steps) {
        Wire.Snapshot snapshot = snapshot();
        Plan plan = new Plan();
        plan.title = "合成备份😀 <>&";
        plan.anchor = "2026-01-01";
        plan.rule = Rule.DAYS;
        if (steps) {
            for (int index = 0; index < 30; index++) {
                plan.steps.add("测".repeat(80));
            }
        }
        snapshot.plans.add(plan);
        LocalDate first = LocalDate.parse(plan.anchor);
        for (int offset = 0; offset < days; offset += 3650) {
            snapshot.instances.addAll(Recurrence.generate(plan, first.plusDays(offset),
                    first.plusDays(Math.min(days, offset + 3650) - 1)));
        }
        return snapshot;
    }

    @Test
    public void historyBeyondTwoMiBRoundTripsWithoutChangingSource() throws IOException {
        Wire.Snapshot original = history(320, true);
        String before = Wire.JSON.toJson(original);
        assertTrue(Capacity.measure(original).bytes > Wire.MAX_BYTES);
        byte[] bytes = encode(original);
        assertTrue(bytes.length > Wire.MAX_BYTES);
        assertEquals(before, Wire.JSON.toJson(decode(bytes)));
        assertEquals(before, Wire.JSON.toJson(original));
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(original));
    }

    @Test
    public void archiveAllowsMoreInstancesThanTransport() throws IOException {
        Wire.Snapshot original = history(12001, false);
        assertEquals(12001, original.instances.size());
        assertThrows(IllegalArgumentException.class, () -> Wire.validate(original));
        assertEquals(12001, decode(encode(original)).instances.size());
    }

    @Test
    public void allKindsAndMultipleEventPagesKeepCompletionAndReopenHistory() throws IOException {
        Wire.Snapshot original = snapshot();
        LocalDate date = LocalDate.parse("2026-09-28");
        for (Kind kind : Kind.values()) {
            Plan plan = new Plan();
            plan.title = "合成分类备份";
            plan.kind = kind;
            plan.rule = Rule.DAYS;
            plan.count = 1;
            plan.anchor = date.toString();
            original.plans.add(plan);
            Instance instance = Recurrence.generate(plan, date, date).get(0);
            original.instances.add(instance);
            if (kind == Kind.TODO) {
                for (int index = 1; index <= 65; index++) {
                    Event event = new Event();
                    event.instanceId = instance.id;
                    event.snapshot = instance;
                    event.action = index % 2 == 0 ? Action.REOPEN : Action.COMPLETE;
                    event.confirmedAt = index;
                    event.actualAt = (long) index;
                    event.logical = index;
                    event.deviceId = "synthetic";
                    original.events.add(event);
                }
            }
        }
        Wire.Snapshot restored = decode(encode(original));
        assertEquals(65, restored.events.size());
        assertEquals(Wire.JSON.toJson(original), Wire.JSON.toJson(restored));
    }

    @Test
    public void emptyArchiveStillHasValidatedMetadata() throws IOException {
        Wire.Snapshot original = snapshot();
        original.settingsVersion = 5;
        assertEquals(Wire.JSON.toJson(original), Wire.JSON.toJson(decode(encode(original))));
    }

    @Test
    public void currentAndLegacyJsonBackupsRemainReadable() throws IOException {
        Wire.Snapshot original = history(1, false);
        assertEquals(1, decode(Wire.encode(original)).plans.size());
        original.protocol = 1;
        original.schema = 2;
        Wire.Snapshot restored = decode(Wire.JSON.toJson(original).getBytes(StandardCharsets.UTF_8));
        assertEquals(2, restored.protocol);
        assertEquals(4, restored.schema);
    }

    @Test
    public void truncatedHeaderFrameOrFooterNeverReturnsPartialData() throws IOException {
        byte[] bytes = encode(history(2, false));
        for (int length : new int[] {0, 8, 22, bytes.length / 2, bytes.length - 33, bytes.length - 1}) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(Exception.class, () -> decode(truncated));
        }
    }

    @Test
    public void checksumAndTrailingGarbageAreRejected() throws IOException {
        byte[] bytes = encode(history(2, false));
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> decode(bytes));
        byte[] trailing = Arrays.copyOf(encode(snapshot()), encode(snapshot()).length + 1);
        assertThrows(IllegalArgumentException.class, () -> decode(trailing));
    }

    private byte[] pages(Wire.Snapshot... pages) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        DigestOutputStream checked = new DigestOutputStream(bytes, digest);
        DataOutputStream out = new DataOutputStream(checked);
        out.write("X3SCHEDULE-BACKUP/1\n".getBytes(StandardCharsets.US_ASCII));
        for (Wire.Snapshot page : pages) {
            byte[] body = Wire.encode(page);
            out.writeInt(body.length);
            out.write(body);
        }
        out.writeInt(0);
        checked.on(false);
        out.write(digest.digest());
        return bytes.toByteArray();
    }

    @Test
    public void mixedSourcesOrRevisionsRejectedEvenWithCorrectDigest() throws Exception {
        Wire.Snapshot page = snapshot();
        page.deviceId = "another-phone";
        byte[] bytes = pages(snapshot(), page);
        assertThrows(IllegalArgumentException.class, () -> decode(bytes));
        page.deviceId = "synthetic";
        page.revision++;
        byte[] revision = pages(snapshot(), page);
        assertThrows(IllegalArgumentException.class, () -> decode(revision));
    }

    @Test
    public void duplicatesAcrossPagesAreRejectedAfterIntegrityCheck() throws Exception {
        Wire.Snapshot page = history(1, false);
        byte[] bytes = pages(snapshot(), page, page);
        assertThrows(IllegalArgumentException.class, () -> decode(bytes));
    }

    @Test
    public void missingOrNonemptyHeaderIsRejected() throws Exception {
        byte[] missing = pages();
        assertThrows(IllegalArgumentException.class, () -> decode(missing));
        byte[] nonempty = pages(history(1, false));
        assertThrows(IllegalArgumentException.class, () -> decode(nonempty));
    }

    @Test
    public void negativeAndOversizedFrameLengthsRejectedBeforeAllocation() throws IOException {
        for (int length : new int[] {-1, Wire.MAX_BYTES + 1, Integer.MAX_VALUE}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write("X3SCHEDULE-BACKUP/1\n".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(length);
            assertThrows(IllegalArgumentException.class, () -> decode(bytes.toByteArray()));
        }
    }

    @Test
    public void watchSnapshotsAndOversizedLegacyJsonAreRejected() throws IOException {
        Wire.Snapshot watch = snapshot();
        watch.source = "watch";
        assertThrows(IllegalArgumentException.class, () -> encode(watch));
        assertThrows(IllegalArgumentException.class, () -> decode(Wire.encode(watch)));
        byte[] large = new byte[Wire.MAX_BYTES + 1];
        Arrays.fill(large, (byte) ' ');
        assertThrows(IllegalArgumentException.class, () -> decode(large));
    }

    @Test
    public void oversizedArchiveWriteStopsBeforeCrossingLimit() {
        Wire.Snapshot original = history(4500, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> Backup.write(original, bytes));
        assertTrue(bytes.size() <= Backup.MAX_BYTES);
        assertThrows(Exception.class, () -> decode(bytes.toByteArray()));
    }

    @Test
    public void totalReadLimitCannotBeBypassedWithIndividuallyValidFrames() throws IOException {
        String json = Wire.JSON.toJson(snapshot());
        json = json.substring(0, json.length() - 1) + ",\"padding\":\"" + "x".repeat(1900000) + "\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(body.length);
        out.write(body);
        byte[] bytes = frame.toByteArray();
        List<InputStream> parts = new ArrayList<>();
        parts.add(new ByteArrayInputStream("X3SCHEDULE-BACKUP/1\n".getBytes(StandardCharsets.US_ASCII)));
        for (int index = 0; index < 18; index++) {
            parts.add(new ByteArrayInputStream(bytes));
        }
        try (InputStream input = new SequenceInputStream(Collections.enumeration(parts))) {
            assertThrows(IllegalArgumentException.class, () -> Backup.read(input));
        }
    }

    @Test
    public void recordLimitRejectsBeforeWritingAndPageFloodIsBounded() throws Exception {
        Wire.Snapshot original = history(1, false);
        original.instances = new ArrayList<>(Collections.nCopies(Backup.MAX_ITEMS + 1, original.instances.get(0)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> Backup.write(original, bytes));
        assertEquals(0, bytes.size());
        Wire.Snapshot[] emptyPages = new Wire.Snapshot[4097];
        Arrays.fill(emptyPages, snapshot());
        byte[] flood = pages(emptyPages);
        assertThrows(IllegalArgumentException.class, () -> decode(flood));
    }

    @Test
    public void streamIsOwnedByCaller() throws IOException {
        boolean[] closed = {false};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        Backup.write(snapshot(), out);
        assertFalse(closed[0]);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray()) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        Backup.read(in);
        assertFalse(closed[0]);
    }
}