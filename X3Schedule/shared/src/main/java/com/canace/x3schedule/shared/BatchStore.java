package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import com.canace.x3schedule.domain.Batch;
import com.canace.x3schedule.domain.Wire;

/** Called under Store's monitor. Each durable transition owns one SQLite transaction. */
final class BatchStore {
    private final Store store;
    private final SQLiteDatabase db;

    BatchStore(Store store) {
        this.store = store;
        db = store.getWritableDatabase();
    }

    byte[] outgoing() {
        db.beginTransaction();
        try {
            Batch.Manifest manifest = manifest("batch_out");
            if (manifest == null) {
                manifest = create();
            }
            int next = next();
            byte[] packet = Batch.packet(manifest, next, payload("batch_out_parts", next));
            db.setTransactionSuccessful();
            return packet;
        } finally {
            db.endTransaction();
        }
    }

    private Batch.Manifest create() {
        int[] index = {0};
        Batch.Manifest manifest = Batch.prepare(store.snapshot(false), bytes -> {
            ContentValues part = new ContentValues();
            part.put("seq", index[0]++);
            part.put("body", bytes);
            db.insertOrThrow("batch_out_parts", null, part);
        });
        ContentValues row = new ContentValues();
        row.put("slot", 1);
        row.put("manifest", Wire.JSON.toJson(manifest));
        row.put("next", 0);
        db.insertOrThrow("batch_out", null, row);
        return manifest;
    }

    boolean acknowledge(Batch.Ack ack) {
        db.beginTransaction();
        try {
            Batch.Manifest manifest = manifest("batch_out");
            require(manifest != null, "没有待确认批次");
            int index = next();
            byte[] packet = Batch.packet(manifest, index, payload("batch_out_parts", index));
            int next = Batch.next(manifest, index, packet, ack);
            boolean done = next == manifest.count;
            if (done) {
                long prior = Long.parseLong(store.meta("acked_revision", "0"));
                store.putMeta("acked_revision", Long.toString(Math.max(prior, manifest.revision)));
                store.putMeta("last_sync", Long.toString(System.currentTimeMillis()));
                // Delete only this fully committed delivery copy; authoritative history is retained.
                db.delete("batch_out_parts", null, null);
                db.delete("batch_out", "slot=1", null);
            } else {
                ContentValues row = new ContentValues();
                row.put("next", next);
                db.update("batch_out", row, "slot=1", null);
            }
            db.setTransactionSuccessful();
            return done;
        } finally {
            db.endTransaction();
        }
    }

    Batch.Ack receive(byte[] packet) {
        Batch.Part part = Batch.decode(packet);
        require(store.phone ? part.manifest.source.equals("watch") : part.manifest.source.equals("phone"),
                "同步方向无效");
        db.beginTransaction();
        try {
            String hash = Wire.digest(packet);
            boolean committed = receipt(part, hash);
            if (!committed) {
                stage(part, hash);
                committed = complete(part);
            }
            db.setTransactionSuccessful();
            return Batch.ack(part, packet, committed && part.index == part.manifest.count - 1);
        } finally {
            db.endTransaction();
        }
    }

    private boolean receipt(Batch.Part part, String hash) {
        try (Cursor row = db.rawQuery("SELECT manifest,digests FROM batch_receipts WHERE id=?",
                new String[] {part.manifest.id})) {
            if (!row.moveToFirst()) {
                return false;
            }
            require(row.getString(0).equals(Wire.JSON.toJson(part.manifest)), "已完成批次内容改变");
            String[] digests = Wire.JSON.fromJson(row.getString(1), String[].class);
            require(digests.length == part.manifest.count && hash.equals(digests[part.index]), "已完成分批内容改变");
            return true;
        }
    }

    private void stage(Batch.Part part, String digest) {
        Batch.Manifest current = manifest("batch_in");
        if (current != null && !current.id.equals(part.manifest.id)) {
            require(part.index == 0 && (!current.deviceId.equals(part.manifest.deviceId)
                    || part.manifest.revision >= current.revision), "另一份批次尚未完成");
            // Only replace uncommitted staging, never application records or the sender's durable queue.
            db.delete("batch_in_parts", null, null);
            db.delete("batch_in", "slot=1", null);
            current = null;
        }
        if (current == null) {
            ContentValues row = new ContentValues();
            row.put("slot", 1);
            row.put("manifest", Wire.JSON.toJson(part.manifest));
            db.insertOrThrow("batch_in", null, row);
        } else {
            require(Wire.JSON.toJson(current).equals(Wire.JSON.toJson(part.manifest)), "批次元数据改变");
        }
        savePart(part, digest);
    }

    private void savePart(Batch.Part part, String digest) {
        try (Cursor row = db.rawQuery("SELECT digest FROM batch_in_parts WHERE seq=?",
                new String[] {Integer.toString(part.index)})) {
            if (row.moveToFirst()) {
                require(digest.equals(row.getString(0)), "重传分批内容改变");
                return;
            }
        }
        ContentValues row = new ContentValues();
        row.put("seq", part.index);
        row.put("body", part.payload);
        row.put("digest", digest);
        db.insertOrThrow("batch_in_parts", null, row);
    }

    private boolean complete(Batch.Part part) {
        if (part.index != part.manifest.count - 1
                || DatabaseUtils.queryNumEntries(db, "batch_in_parts") != part.manifest.count) {
            return false;
        }
        Wire.Snapshot snapshot = Batch.assemble(part.manifest, index -> payload("batch_in_parts", index));
        store.applyBatch(snapshot);
        String[] digests = new String[part.manifest.count];
        try (Cursor rows = db.rawQuery("SELECT seq,digest FROM batch_in_parts ORDER BY seq", null)) {
            while (rows.moveToNext()) {
                digests[rows.getInt(0)] = rows.getString(1);
            }
        }
        ContentValues receipt = new ContentValues();
        receipt.put("id", part.manifest.id);
        receipt.put("manifest", Wire.JSON.toJson(part.manifest));
        receipt.put("digests", Wire.JSON.toJson(digests));
        db.insertOrThrow("batch_receipts", null, receipt);
        // The receipt and application merge commit together before acknowledged staging is discarded.
        db.delete("batch_in_parts", null, null);
        db.delete("batch_in", "slot=1", null);
        return true;
    }

    private int next() {
        try (Cursor row = db.rawQuery("SELECT next FROM batch_out WHERE slot=1", null)) {
            require(row.moveToFirst(), "发送进度缺失");
            return row.getInt(0);
        }
    }

    private Batch.Manifest manifest(String table) {
        try (Cursor row = db.rawQuery("SELECT manifest FROM " + table + " WHERE slot=1", null)) {
            if (!row.moveToFirst()) {
                return null;
            }
            Batch.Manifest manifest = Wire.JSON.fromJson(row.getString(0), Batch.Manifest.class);
            manifest.validate();
            return manifest;
        }
    }

    private byte[] payload(String table, int index) {
        try (Cursor row = db.rawQuery("SELECT body FROM " + table + " WHERE seq=?",
                new String[] {Integer.toString(index)})) {
            require(row.moveToFirst(), "分批内容缺失");
            return row.getBlob(0);
        }
    }
}
