package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Phone-only local backup; the shared archive codec also serves bounded sync batches. */
public final class Backup {
    public static final int MAX_BYTES = SnapshotArchive.MAX_BYTES;
    public static final int MAX_ITEMS = SnapshotArchive.MAX_ITEMS;

    private Backup() {
    }

    public static void validate(Wire.Snapshot snapshot) {
        SnapshotArchive.validate(snapshot);
        require("phone".equals(snapshot.source), "需手机备份");
        ProgressHistory.validate(snapshot.events);
    }

    public static void write(Wire.Snapshot snapshot, OutputStream output) throws IOException {
        validate(snapshot);
        SnapshotArchive.write(snapshot, output);
    }

    public static Wire.Snapshot read(InputStream input) throws IOException {
        Wire.Snapshot snapshot = SnapshotArchive.read(input);
        validate(snapshot);
        return snapshot;
    }
}
