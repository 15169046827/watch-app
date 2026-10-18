package com.canace.x3schedule.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BatchTest {
    private Wire.Snapshot history() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        snapshot.revision = 42;
        Plan plan = new Plan();
        plan.title = "合成分批历史";
        plan.anchor = "2026-01-01";
        plan.rule = Rule.DAYS;
        for (int index = 0; index < 30; index++) {
            plan.steps.add("测".repeat(80));
        }
        snapshot.plans.add(plan);
        LocalDate first = LocalDate.parse(plan.anchor);
        snapshot.instances.addAll(Recurrence.generate(plan, first, first.plusDays(319)));
        return snapshot;
    }

    private Batch.Ack ack(Batch.Manifest manifest, List<byte[]> pages, int index, boolean committed) {
        byte[] packet = Batch.packet(manifest, index, pages.get(index));
        return Batch.ack(Batch.decode(packet), packet, committed);
    }

    @Test
    public void longHistorySurvivesPhysicalFragmentsAndWholeBatchAssembly() {
        Wire.Snapshot source = history();
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(source, pages::add);
        assertTrue(manifest.bytes > Wire.MAX_BYTES);
        assertTrue(manifest.count > 1);
        List<byte[]> received = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            byte[] packet = Batch.packet(manifest, index, pages.get(index));
            byte[] framed = Frames.pack(3, packet);
            Frames frames = new Frames();
            byte[] complete = null;
            for (int offset = 0; offset < framed.length; offset += 180) {
                complete = frames.add(Arrays.copyOfRange(framed, offset, Math.min(framed.length, offset + 180)));
            }
            received.add(Batch.decode(Frames.body(complete)).payload);
        }
        assertEquals(Wire.JSON.toJson(source), Wire.JSON.toJson(Batch.assemble(manifest, received::get)));
        assertEquals(manifest.bytes, Capacity.measureBatch(source).bytes);
        assertFalse(Capacity.measureBatch(source).exceeded());
    }

    @Test
    public void lostAckReplaysExactPageAndOnlyFinalCommitCompletes() {
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(history(), pages::add);
        for (int index = 0; index < pages.size(); index++) {
            byte[] packet = Batch.packet(manifest, index, pages.get(index));
            assertArrayEquals(packet, Batch.packet(manifest, index, pages.get(index)));
            boolean last = index == pages.size() - 1;
            Batch.Ack receipt = ack(manifest, pages, index, last);
            Batch.Ack decoded = Batch.decodeAck(Batch.encodeAck(receipt));
            assertEquals(index + 1, Batch.next(manifest, index, packet, decoded));
        }
    }

    @Test
    public void missingReceiverProgressRewindsWithoutPretendingCommitted() {
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(history(), pages::add);
        int last = pages.size() - 1;
        byte[] packet = Batch.packet(manifest, last, pages.get(last));
        assertEquals(0, Batch.next(manifest, last, packet, ack(manifest, pages, last, false)));
        assertThrows(IllegalArgumentException.class, () -> Batch.assemble(manifest,
                index -> index == 0 ? null : pages.get(index)));
    }

    @Test
    public void restartCanReconstructManifestCursorAndExactPackets() {
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest original = Batch.prepare(history(), pages::add);
        Batch.Manifest restored = Wire.JSON.fromJson(Wire.JSON.toJson(original), Batch.Manifest.class);
        int cursor = original.count / 2;
        assertArrayEquals(Batch.packet(original, cursor, pages.get(cursor)),
                Batch.packet(restored, cursor, pages.get(cursor)));
        assertEquals(history().instances.size(), Batch.assemble(restored, pages::get).instances.size());
    }

    @Test
    public void mixedCorruptedOrTruncatedPagesNeverReturnSnapshot() {
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(history(), pages::add);
        byte[] changed = pages.get(1).clone();
        changed[changed.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> Batch.assemble(manifest,
                index -> index == 1 ? changed : pages.get(index)));
        assertThrows(IllegalArgumentException.class, () -> Batch.assemble(manifest,
                index -> index == 1 ? pages.get(0) : pages.get(index)));
        assertThrows(IllegalArgumentException.class, () -> Batch.packet(manifest, 0,
                Arrays.copyOf(pages.get(0), Batch.PAGE_BYTES - 1)));
        manifest.digest = "0".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> Batch.assemble(manifest, pages::get));
    }

    @Test
    public void envelopeIdentityMustMatchArchiveEvenWithCorrectDigest() {
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(history(), pages::add);
        manifest.revision++;
        assertThrows(IllegalArgumentException.class, () -> Batch.assemble(manifest, pages::get));
    }

    @Test
    public void wrongStaleAndEarlyCommitAcksCannotAdvance() {
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(history(), pages::add);
        byte[] first = Batch.packet(manifest, 0, pages.get(0));
        Batch.Ack receipt = ack(manifest, pages, 0, true);
        assertThrows(IllegalArgumentException.class, () -> Batch.next(manifest, 0, first, receipt));
        receipt.committed = false;
        receipt.digest = "0".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> Batch.next(manifest, 0, first, receipt));
        Batch.Ack stale = ack(manifest, pages, 0, false);
        assertThrows(IllegalArgumentException.class, () -> Batch.next(manifest, 1,
                Batch.packet(manifest, 1, pages.get(1)), stale));
    }

    @Test
    public void watchOwnEventsUseSameBatchButCannotContainDefinitions() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "watch";
        snapshot.deviceId = "synthetic-watch";
        Event event = new Event();
        event.instanceId = "p:2026-01-01";
        event.deviceId = snapshot.deviceId;
        event.logical = 1;
        event.confirmedAt = 1;
        event.actualAt = 1L;
        event.action = Action.COMPLETE;
        snapshot.events.add(event);
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(snapshot, pages::add);
        assertEquals(event.id, Batch.assemble(manifest, pages::get).events.get(0).id);
        snapshot.plans.add(history().plans.get(0));
        assertThrows(IllegalArgumentException.class, () -> Batch.prepare(snapshot, bytes -> { }));
    }

    @Test
    public void batchRequiresArchiveMagicRatherThanLegacyJsonFallback() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        byte[] legacy = Wire.encode(snapshot);
        Batch.Manifest manifest = new Batch.Manifest();
        manifest.id = snapshot.id;
        manifest.source = snapshot.source;
        manifest.deviceId = snapshot.deviceId;
        manifest.revision = snapshot.revision;
        manifest.bytes = legacy.length;
        manifest.count = 1;
        manifest.digest = Wire.digest(legacy);
        assertThrows(IllegalArgumentException.class, () -> Batch.assemble(manifest, index -> legacy));
    }

    @Test
    public void malformedPacketsAcksAndFrameKindsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> Batch.decode(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> Batch.decode(new byte[Batch.MAX_PACKET + 1]));
        assertThrows(IllegalArgumentException.class, () -> Batch.decodeAck(new byte[257]));
        assertThrows(IllegalArgumentException.class, () -> new Frames().add(
                ByteBuffer.allocate(5).put((byte) 3).putInt(Batch.MAX_PACKET + 1).array()));
        assertThrows(IllegalArgumentException.class, () -> Frames.pack(5, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> Frames.pack(6, new byte[0]));
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(history(), pages::add);
        byte[] valid = Batch.encodeAck(ack(manifest, pages, 0, false));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> Batch.decodeAck(trailing));
        valid[valid.length - 1] = 2;
        assertThrows(IllegalArgumentException.class, () -> Batch.decodeAck(valid));
    }

    @Test
    public void unequalDirectionsFinishUsingPollsWithoutEndlessNewPhoneBatches() {
        for (int[] counts : new int[][] {{1, 5}, {5, 1}, {3, 7}, {7, 3}}) {
            BatchExchange exchange = new BatchExchange();
            int sent = 0;
            int received = 0;
            int rounds = 0;
            while (exchange.nextKind(false) != 0 && rounds++ < 30) {
                if (exchange.nextKind(false) == 3) {
                    exchange.localAck(++sent == counts[0]);
                }
                exchange.remoteAck(++received % counts[1] == 0);
            }
            assertEquals(counts[0], sent);
            assertEquals(0, exchange.nextKind(false));
            assertTrue(rounds <= counts[0] + counts[1]);
        }
    }

    @Test
    public void pendingChangesStartAfterBothSnapshotsCommit() {
        BatchExchange exchange = new BatchExchange();
        exchange.localAck(true);
        assertEquals(5, exchange.nextKind(true));
        exchange.remoteAck(true);
        assertEquals(3, exchange.nextKind(true));
        // A new transfer must not reuse either direction's prior commit acknowledgement.
        exchange.localAck(true);
        assertEquals(5, exchange.nextKind(false));
        exchange.remoteAck(true);
        assertEquals(0, exchange.nextKind(false));
    }

    @Test
    public void changesDuringRemoteFirstCommitDoNotEndTheLocalTransfer() {
        BatchExchange exchange = new BatchExchange();
        exchange.remoteAck(true);
        assertEquals(3, exchange.nextKind(true));
        exchange.localAck(false);
        assertEquals(3, exchange.nextKind(true));
        exchange.localAck(true);
        assertEquals(3, exchange.nextKind(true));
        exchange.localAck(true);
        exchange.remoteAck(true);
        assertEquals(0, exchange.nextKind(false));
    }

}