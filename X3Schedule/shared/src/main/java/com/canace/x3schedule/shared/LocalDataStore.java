package com.canace.x3schedule.shared;

import android.database.sqlite.SQLiteDatabase;

/** Transactional local-only maintenance; it never creates a deletion to send to the peer. */
final class LocalDataStore {
    private LocalDataStore() {
    }

    static void clearBusiness(Store store) {
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            delete(db, "plans", "instances", "events", "suggestions", "outbox", "legacy_outbox_v1",
                    "inbox", "batch_out", "batch_out_parts", "batch_in", "batch_in_parts", "batch_receipts");
            db.execSQL("DELETE FROM meta WHERE key NOT IN ('settings','settings_version','privacy_notice',"
                    + "'device_id','clock','peer','background_sync')");
            store.putMeta("sync_paused", "true");
            store.putMeta("revision", "0");
            store.putMeta("acked_revision", "0");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static void clearTransferCache(Store store) {
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            delete(db, "batch_out", "batch_out_parts", "batch_in", "batch_in_parts");
            // A peer may still have an ACK for an earlier snapshot; restart with a new revision.
            long revision = Long.parseLong(store.meta("revision", "0"));
            store.putMeta("revision", Long.toString(revision + 1));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void delete(SQLiteDatabase db, String... tables) {
        for (String table : tables) {
            db.delete(table, null, null);
        }
    }
}
