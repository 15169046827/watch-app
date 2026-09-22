package com.canace.x3voiceledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class LedgerStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "voice_ledger.db";
    private static final int DATABASE_VERSION = 5;

    LedgerStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE ledger_entries ("
                        + "id TEXT PRIMARY KEY,"
                        + "created_at INTEGER NOT NULL,"
                        + "updated_at INTEGER NOT NULL,"
                        + "transaction_time INTEGER NOT NULL,"
                        + "amount_cents INTEGER NOT NULL,"
                        + "note TEXT NOT NULL,"
                        + "category TEXT NOT NULL,"
                        + "category_parent TEXT NOT NULL DEFAULT '',"
                        + "category_child TEXT NOT NULL DEFAULT '',"
                        + "account TEXT NOT NULL DEFAULT '',"
                        + "book_name TEXT NOT NULL DEFAULT '',"
                        + "tags TEXT NOT NULL DEFAULT '',"
                        + "raw_text TEXT NOT NULL,"
                        + "source TEXT NOT NULL DEFAULT 'watch_voice',"
                        + "latitude REAL,"
                        + "longitude REAL,"
                        + "location_accuracy REAL,"
                        + "location_time INTEGER,"
                        + "location_label TEXT NOT NULL DEFAULT '',"
                        + "sync_state INTEGER NOT NULL DEFAULT 0)");
        db.execSQL(
                "CREATE INDEX ledger_entries_transaction_time ON ledger_entries(transaction_time"
                        + " DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN transaction_time INTEGER NOT NULL"
                            + " DEFAULT 0");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN latitude REAL");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN longitude REAL");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN location_accuracy REAL");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN location_time INTEGER");
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN location_label TEXT NOT NULL DEFAULT"
                            + " ''");
            db.execSQL(
                    "UPDATE ledger_entries SET updated_at = created_at, transaction_time ="
                            + " created_at");
            db.execSQL(
                    "CREATE INDEX ledger_entries_transaction_time ON"
                            + " ledger_entries(transaction_time DESC)");
        }
        if (oldVersion < 3) {
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN category_parent TEXT NOT NULL DEFAULT"
                            + " ''");
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN category_child TEXT NOT NULL DEFAULT"
                            + " ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN book_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 4) {
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN source TEXT NOT NULL DEFAULT"
                            + " 'watch_voice'");
        }
        if (oldVersion < 5) {
            db.execSQL(
                    "UPDATE ledger_entries SET updated_at = created_at WHERE updated_at <= 0");
            db.execSQL(
                    "UPDATE ledger_entries SET transaction_time = created_at"
                            + " WHERE transaction_time <= 0");
        }
    }

    void insert(LedgerEntry entry) {
        ContentValues values = new ContentValues();
        values.put("id", entry.id);
        values.put("created_at", entry.createdAt);
        values.put("updated_at", entry.updatedAt);
        values.put("transaction_time", entry.transactionTime);
        values.put("amount_cents", entry.amountCents);
        values.put("note", entry.note);
        values.put("category", entry.category);
        values.put("category_parent", entry.categoryParent);
        values.put("category_child", entry.categoryChild);
        values.put("account", entry.account);
        values.put("book_name", entry.bookName);
        values.put("tags", entry.tags);
        values.put("raw_text", entry.rawText);
        values.put("source", entry.source);
        if (entry.latitude != null) {
            values.put("latitude", entry.latitude);
        }
        if (entry.longitude != null) {
            values.put("longitude", entry.longitude);
        }
        if (entry.locationAccuracy != null) {
            values.put("location_accuracy", entry.locationAccuracy);
        }
        if (entry.locationTime != null) {
            values.put("location_time", entry.locationTime);
        }
        values.put("location_label", entry.locationLabel);
        values.put("sync_state", 0);
        getWritableDatabase().insertOrThrow("ledger_entries", null, values);
    }

    boolean update(LedgerEntry entry) {
        ContentValues values = new ContentValues();
        values.put("updated_at", entry.updatedAt);
        values.put("transaction_time", entry.transactionTime);
        values.put("amount_cents", entry.amountCents);
        values.put("note", entry.note);
        values.put("category", entry.category);
        values.put("category_parent", entry.categoryParent);
        values.put("category_child", entry.categoryChild);
        values.put("account", entry.account);
        values.put("book_name", entry.bookName);
        values.put("tags", entry.tags);
        values.put("raw_text", entry.rawText);
        values.put("source", entry.source);
        if (entry.latitude == null) {
            values.putNull("latitude");
        } else {
            values.put("latitude", entry.latitude);
        }
        if (entry.longitude == null) {
            values.putNull("longitude");
        } else {
            values.put("longitude", entry.longitude);
        }
        if (entry.locationAccuracy == null) {
            values.putNull("location_accuracy");
        } else {
            values.put("location_accuracy", entry.locationAccuracy);
        }
        if (entry.locationTime == null) {
            values.putNull("location_time");
        } else {
            values.put("location_time", entry.locationTime);
        }
        values.put("location_label", entry.locationLabel);
        values.put("sync_state", 0);
        return getWritableDatabase()
                        .update("ledger_entries", values, "id = ?", new String[] {entry.id})
                == 1;
    }

    LedgerEntry find(String id) {
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "ledger_entries",
                                entryColumns(),
                                "id = ?",
                                new String[] {id},
                                null,
                                null,
                                null,
                                "1")) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    int count() {
        try (Cursor cursor =
                getReadableDatabase().rawQuery("SELECT COUNT(*) FROM ledger_entries", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    int pendingCount() {
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT COUNT(*) FROM ledger_entries WHERE sync_state = 0", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    LedgerEntry nextPending() {
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "ledger_entries",
                                entryColumns(),
                                "sync_state = 0",
                                null,
                                null,
                                null,
                                "created_at ASC",
                                "1")) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    boolean markSynced(String id) {
        ContentValues values = new ContentValues();
        values.put("sync_state", 1);
        return getWritableDatabase()
                        .update(
                                "ledger_entries",
                                values,
                                "id = ? AND sync_state = 0",
                                new String[] {id})
                == 1;
    }

    boolean delete(String id) {
        return getWritableDatabase().delete("ledger_entries", "id = ?", new String[] {id}) == 1;
    }

    int deleteBySource(String source) {
        return getWritableDatabase()
                .delete("ledger_entries", "source = ?", new String[] {source});
    }

    int deleteAll() {
        return getWritableDatabase().delete("ledger_entries", null, null);
    }

    List<LedgerEntry> recent(int limit) {
        List<LedgerEntry> result = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "ledger_entries",
                                entryColumns(),
                                null,
                                null,
                                null,
                                null,
                                "transaction_time DESC",
                                Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                result.add(fromCursor(cursor));
            }
        }
        return result;
    }

    private static String[] entryColumns() {
        return new String[] {
            "id",
            "created_at",
            "updated_at",
            "transaction_time",
            "amount_cents",
            "note",
            "category",
            "category_parent",
            "category_child",
            "account",
            "book_name",
            "tags",
            "raw_text",
            "source",
            "latitude",
            "longitude",
            "location_accuracy",
            "location_time",
            "location_label"
        };
    }

    private static LedgerEntry fromCursor(Cursor cursor) {
        return new LedgerEntry(
                cursor.getString(0),
                cursor.getLong(1),
                cursor.getLong(2),
                cursor.getLong(3),
                cursor.getLong(4),
                cursor.getString(5),
                cursor.getString(6),
                cursor.getString(7),
                cursor.getString(8),
                cursor.getString(9),
                cursor.getString(10),
                cursor.getString(11),
                cursor.getString(12),
                cursor.getString(13),
                nullableDouble(cursor, 14),
                nullableDouble(cursor, 15),
                nullableFloat(cursor, 16),
                nullableLong(cursor, 17),
                cursor.getString(18));
    }

    private static Double nullableDouble(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getDouble(column);
    }

    private static Float nullableFloat(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getFloat(column);
    }

    private static Long nullableLong(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getLong(column);
    }
}
