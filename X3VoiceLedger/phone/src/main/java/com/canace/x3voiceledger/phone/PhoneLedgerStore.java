package com.canace.x3voiceledger.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PhoneLedgerStore extends SQLiteOpenHelper {
    static final int STATE_PENDING = 0;
    static final int STATE_ARCHIVED = 1;

    PhoneLedgerStore(Context context) {
        super(context, "phone_ledger.db", null, 4);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ledger_entries (" +
                "id TEXT PRIMARY KEY, updated_at INTEGER NOT NULL, transaction_time INTEGER NOT NULL," +
                "amount_cents INTEGER NOT NULL, note TEXT NOT NULL, category TEXT NOT NULL," +
                "category_parent TEXT NOT NULL DEFAULT '', category_child TEXT NOT NULL DEFAULT ''," +
                "account TEXT NOT NULL DEFAULT '', book_name TEXT NOT NULL DEFAULT ''," +
                "tags TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'watch_voice'," +
                "location_label TEXT NOT NULL, received_at INTEGER NOT NULL, full_json TEXT NOT NULL," +
                "yimu_state INTEGER NOT NULL DEFAULT 0, yimu_launched_at INTEGER," +
                "yimu_parent TEXT NOT NULL DEFAULT '', yimu_child TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX ledger_transaction_time ON ledger_entries(transaction_time DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN yimu_state INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN yimu_launched_at INTEGER");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN yimu_parent TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN yimu_child TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN category_parent TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN category_child TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN book_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN source TEXT NOT NULL DEFAULT 'watch_voice'");
        }
    }

    void upsert(JSONObject payload) {
        ContentValues values = new ContentValues();
        values.put("id", payload.optString("id"));
        values.put("updated_at", payload.optLong("updatedAt"));
        values.put("transaction_time", payload.optLong("transactionTime"));
        values.put("amount_cents", payload.optLong("amountCents"));
        values.put("note", payload.optString("note"));
        values.put("category", payload.optString("category"));
        values.put("category_parent", payload.optString("categoryParent"));
        values.put("category_child", payload.optString("categoryChild"));
        values.put("account", payload.optString("account"));
        values.put("book_name", payload.optString("bookName"));
        values.put("tags", payload.optString("tags"));
        values.put("source", payload.optString("source", "watch_voice"));
        values.put("location_label", payload.optString("locationLabel"));
        values.put("received_at", System.currentTimeMillis());
        values.put("full_json", payload.toString());
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("ledger_entries", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.update("ledger_entries", values, "id = ?", new String[]{payload.optString("id")});
    }

    int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM ledger_entries", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    int countByState(int state) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM ledger_entries WHERE yimu_state = ?",
                new String[]{Integer.toString(state)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    boolean delete(String id) {
        return getWritableDatabase().delete("ledger_entries", "id = ?", new String[]{id}) == 1;
    }

    boolean updateDetails(String id, String note, String categoryParent, String categoryChild,
                          String account, String bookName, String tags) {
        ContentValues values = new ContentValues();
        values.put("note", note);
        values.put("category_parent", categoryParent);
        values.put("category_child", categoryChild);
        values.put("account", account);
        values.put("book_name", bookName);
        values.put("tags", tags);
        return getWritableDatabase().update("ledger_entries", values,
                "id = ?", new String[]{id}) == 1;
    }

    boolean markState(String id, int state) {
        ContentValues values = new ContentValues();
        values.put("yimu_state", state);
        if (state == STATE_ARCHIVED) {
            values.put("yimu_launched_at", System.currentTimeMillis());
        } else {
            values.putNull("yimu_launched_at");
        }
        return getWritableDatabase().update("ledger_entries", values,
                "id = ?", new String[]{id}) == 1;
    }

    boolean markArchived(String id, YimuCategoryMapper.Mapping mapping) {
        ContentValues values = new ContentValues();
        values.put("yimu_state", STATE_ARCHIVED);
        values.put("yimu_launched_at", System.currentTimeMillis());
        values.put("yimu_parent", mapping.parent);
        values.put("yimu_child", mapping.child);
        return getWritableDatabase().update("ledger_entries", values,
                "id = ?", new String[]{id}) == 1;
    }

    Entry find(String id) {
        try (Cursor cursor = getReadableDatabase().query("ledger_entries", entryColumns(),
                "id = ?", new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    List<Entry> recentByState(int state, int limit) {
        List<Entry> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("ledger_entries",
                entryColumns(), "yimu_state = ?", new String[]{Integer.toString(state)},
                null, null, "transaction_time DESC", Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                rows.add(fromCursor(cursor));
            }
        }
        return rows;
    }

    private static String[] entryColumns() {
        return new String[]{"id", "note", "amount_cents", "transaction_time", "category",
                "category_parent", "category_child", "account", "book_name", "tags", "source",
                "location_label", "yimu_state", "yimu_parent", "yimu_child"};
    }

    private static Entry fromCursor(Cursor cursor) {
        return new Entry(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6),
                cursor.getString(7), cursor.getString(8), cursor.getString(9), cursor.getString(10),
                cursor.getString(11), cursor.getInt(12), cursor.getString(13), cursor.getString(14));
    }

    static final class Entry {
        final String id;
        final String note;
        final long amountCents;
        final long transactionTime;
        final String category;
        final String categoryParent;
        final String categoryChild;
        final String account;
        final String bookName;
        final String tags;
        final String source;
        final String locationLabel;
        final int yimuState;
        final String yimuParent;
        final String yimuChild;

        Entry(String id, String note, long amountCents, long transactionTime,
              String category, String categoryParent, String categoryChild, String account,
              String bookName, String tags, String source, String locationLabel, int yimuState,
              String yimuParent, String yimuChild) {
            this.id = id;
            this.note = note;
            this.amountCents = amountCents;
            this.transactionTime = transactionTime;
            this.category = category;
            this.categoryParent = categoryParent;
            this.categoryChild = categoryChild;
            this.account = account;
            this.bookName = bookName;
            this.tags = tags;
            this.source = source;
            this.locationLabel = locationLabel;
            this.yimuState = yimuState;
            this.yimuParent = yimuParent;
            this.yimuChild = yimuChild;
        }

        YimuCategoryMapper.Mapping archivedMapping() {
            return yimuParent.isEmpty() ? null : new YimuCategoryMapper.Mapping(yimuParent, yimuChild);
        }

        YimuCategoryMapper.Mapping watchSuggestedMapping() {
            return categoryParent.isEmpty() ? null
                    : new YimuCategoryMapper.Mapping(categoryParent, categoryChild);
        }
    }
}
