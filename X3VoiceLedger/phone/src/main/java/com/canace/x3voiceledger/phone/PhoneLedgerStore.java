package com.canace.x3voiceledger.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class PhoneLedgerStore extends SQLiteOpenHelper {
    static final int STATE_PENDING = 0;
    static final int STATE_ARCHIVED = 1;

    PhoneLedgerStore(Context context) {
        super(context, "phone_ledger.db", null, 5);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE ledger_entries (id TEXT PRIMARY KEY, updated_at INTEGER NOT NULL,"
                    + " transaction_time INTEGER NOT NULL,amount_cents INTEGER NOT NULL, note TEXT"
                    + " NOT NULL, category TEXT NOT NULL,category_parent TEXT NOT NULL DEFAULT '',"
                    + " category_child TEXT NOT NULL DEFAULT '',account TEXT NOT NULL DEFAULT '',"
                    + " book_name TEXT NOT NULL DEFAULT '',tags TEXT NOT NULL DEFAULT '', source"
                    + " TEXT NOT NULL DEFAULT 'watch_voice',location_label TEXT NOT NULL,"
                    + " received_at INTEGER NOT NULL, full_json TEXT NOT NULL,yimu_state INTEGER"
                    + " NOT NULL DEFAULT 0, yimu_launched_at INTEGER,yimu_parent TEXT NOT NULL"
                    + " DEFAULT '', yimu_child TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX ledger_transaction_time ON ledger_entries(transaction_time DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN yimu_state INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN yimu_launched_at INTEGER");
        }
        if (oldVersion < 3) {
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN yimu_parent TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN yimu_child TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 4) {
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN category_parent TEXT NOT NULL DEFAULT"
                            + " ''");
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN category_child TEXT NOT NULL DEFAULT"
                            + " ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN book_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ledger_entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
            db.execSQL(
                    "ALTER TABLE ledger_entries ADD COLUMN source TEXT NOT NULL DEFAULT"
                            + " 'watch_voice'");
        }
        if (oldVersion < 5) {
            db.execSQL(
                    "UPDATE ledger_entries SET updated_at = received_at WHERE updated_at <= 0");
        }
    }

    boolean upsert(JSONObject payload) {
        String id = payload.optString("id").trim();
        long incomingUpdatedAt = payload.optLong("updatedAt");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("账单缺少有效 id");
        }
        if (incomingUpdatedAt <= 0L) {
            incomingUpdatedAt =
                    Math.max(payload.optLong("createdAt"), payload.optLong("transactionTime"));
        }
        if (incomingUpdatedAt <= 0L) {
            throw new IllegalArgumentException("账单缺少有效修订时间");
        }
        ContentValues values = payloadValues(payload, id, incomingUpdatedAt);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Long localUpdatedAt = updatedAt(db, id);
            if (!IncomingRevisionPolicy.shouldApply(incomingUpdatedAt, localUpdatedAt)) {
                db.setTransactionSuccessful();
                return false;
            }
            if (localUpdatedAt == null) {
                db.insertOrThrow("ledger_entries", null, values);
            } else {
                db.update("ledger_entries", values, "id = ?", new String[] {id});
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues payloadValues(
            JSONObject payload, String id, long incomingUpdatedAt) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("updated_at", incomingUpdatedAt);
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
        return values;
    }

    private static Long updatedAt(SQLiteDatabase db, String id) {
        try (Cursor cursor =
                db.query(
                        "ledger_entries",
                        new String[] {"updated_at"},
                        "id = ?",
                        new String[] {id},
                        null,
                        null,
                        null,
                        "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : null;
        }
    }

    JSONArray exportEntries() throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "ledger_entries",
                                backupColumns(),
                                null,
                                null,
                                null,
                                null,
                                "transaction_time ASC")) {
            while (cursor.moveToNext()) {
                result.put(backupEntry(cursor));
            }
        }
        return result;
    }

    int restoreEntries(JSONArray entries) throws JSONException {
        int restored = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Long localUpdatedAt = updatedAt(getWritableDatabase(), entry.optString("id").trim());
            if (localUpdatedAt != null && localUpdatedAt >= entry.optLong("updatedAt")) {
                continue;
            }
            if (upsert(entry)) {
                restoreImportState(entry);
                restored++;
            }
        }
        return restored;
    }

    private void restoreImportState(JSONObject entry) {
        ContentValues values = new ContentValues();
        int state = entry.optInt("yimuState", STATE_PENDING);
        values.put("yimu_state", state == STATE_ARCHIVED ? STATE_ARCHIVED : STATE_PENDING);
        if (entry.has("yimuLaunchedAt") && !entry.isNull("yimuLaunchedAt")) {
            values.put("yimu_launched_at", entry.optLong("yimuLaunchedAt"));
        } else {
            values.putNull("yimu_launched_at");
        }
        values.put("yimu_parent", entry.optString("yimuParent"));
        values.put("yimu_child", entry.optString("yimuChild"));
        getWritableDatabase()
                .update("ledger_entries", values, "id = ?", new String[] {entry.optString("id")});
    }

    private static JSONObject backupEntry(Cursor cursor) throws JSONException {
        JSONObject entry = new JSONObject();
        String[] columns = backupColumns();
        for (int i = 0; i < columns.length; i++) {
            putColumn(entry, columns[i], cursor, i);
        }
        return entry;
    }

    private static void putColumn(JSONObject entry, String column, Cursor cursor, int index)
            throws JSONException {
        String name = backupName(column);
        if (cursor.isNull(index)) {
            entry.put(name, JSONObject.NULL);
        } else if (cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) {
            entry.put(name, cursor.getLong(index));
        } else {
            entry.put(name, cursor.getString(index));
        }
    }

    private static String backupName(String column) {
        switch (column) {
            case "updated_at":
                return "updatedAt";
            case "transaction_time":
                return "transactionTime";
            case "amount_cents":
                return "amountCents";
            case "category_parent":
                return "categoryParent";
            case "category_child":
                return "categoryChild";
            case "book_name":
                return "bookName";
            case "location_label":
                return "locationLabel";
            case "received_at":
                return "receivedAt";
            case "yimu_state":
                return "yimuState";
            case "yimu_launched_at":
                return "yimuLaunchedAt";
            case "yimu_parent":
                return "yimuParent";
            case "yimu_child":
                return "yimuChild";
            default:
                return column;
        }
    }

    private static String[] backupColumns() {
        return new String[] {
            "id",
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
            "source",
            "location_label",
            "received_at",
            "yimu_state",
            "yimu_launched_at",
            "yimu_parent",
            "yimu_child"
        };
    }

    int deleteAll() {
        return getWritableDatabase().delete("ledger_entries", null, null);
    }

    int count() {
        try (Cursor cursor =
                getReadableDatabase().rawQuery("SELECT COUNT(*) FROM ledger_entries", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    int countByState(int state) {
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT COUNT(*) FROM ledger_entries WHERE yimu_state = ?",
                                new String[] {Integer.toString(state)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    boolean delete(String id) {
        return getWritableDatabase().delete("ledger_entries", "id = ?", new String[] {id}) == 1;
    }

    boolean updateDetails(
            String id,
            String note,
            String categoryParent,
            String categoryChild,
            String account,
            String bookName,
            String tags) {
        ContentValues values = new ContentValues();
        values.put("note", note);
        values.put("category_parent", categoryParent);
        values.put("category_child", categoryChild);
        values.put("account", account);
        values.put("book_name", bookName);
        values.put("tags", tags);
        SQLiteDatabase db = getWritableDatabase();
        Long previousUpdatedAt = updatedAt(db, id);
        if (previousUpdatedAt == null) {
            return false;
        }
        values.put("updated_at", Math.max(System.currentTimeMillis(), previousUpdatedAt + 1L));
        return db.update("ledger_entries", values, "id = ?", new String[] {id}) == 1;
    }

    boolean markState(String id, int state) {
        ContentValues values = new ContentValues();
        values.put("yimu_state", state);
        if (state == STATE_ARCHIVED) {
            values.put("yimu_launched_at", System.currentTimeMillis());
        } else {
            values.putNull("yimu_launched_at");
        }
        return getWritableDatabase().update("ledger_entries", values, "id = ?", new String[] {id})
                == 1;
    }

    boolean markArchived(String id, YimuCategoryMapper.Mapping mapping) {
        ContentValues values = new ContentValues();
        values.put("yimu_state", STATE_ARCHIVED);
        values.put("yimu_launched_at", System.currentTimeMillis());
        values.put("yimu_parent", mapping.parent);
        values.put("yimu_child", mapping.child);
        return getWritableDatabase().update("ledger_entries", values, "id = ?", new String[] {id})
                == 1;
    }

    Entry find(String id) {
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

    List<Entry> recentByState(int state, int limit) {
        List<Entry> rows = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "ledger_entries",
                                entryColumns(),
                                "yimu_state = ?",
                                new String[] {Integer.toString(state)},
                                null,
                                null,
                                "transaction_time DESC",
                                Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                rows.add(fromCursor(cursor));
            }
        }
        return rows;
    }

    private static String[] entryColumns() {
        return new String[] {
            "id",
            "note",
            "amount_cents",
            "transaction_time",
            "category",
            "category_parent",
            "category_child",
            "account",
            "book_name",
            "tags",
            "source",
            "location_label",
            "yimu_state",
            "yimu_parent",
            "yimu_child"
        };
    }

    private static Entry fromCursor(Cursor cursor) {
        return new Entry(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getLong(2),
                cursor.getLong(3),
                cursor.getString(4),
                cursor.getString(5),
                cursor.getString(6),
                cursor.getString(7),
                cursor.getString(8),
                cursor.getString(9),
                cursor.getString(10),
                cursor.getString(11),
                cursor.getInt(12),
                cursor.getString(13),
                cursor.getString(14));
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

        Entry(
                String id,
                String note,
                long amountCents,
                long transactionTime,
                String category,
                String categoryParent,
                String categoryChild,
                String account,
                String bookName,
                String tags,
                String source,
                String locationLabel,
                int yimuState,
                String yimuParent,
                String yimuChild) {
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
            return yimuParent.isEmpty()
                    ? null
                    : new YimuCategoryMapper.Mapping(yimuParent, yimuChild);
        }

        YimuCategoryMapper.Mapping watchSuggestedMapping() {
            return categoryParent.isEmpty()
                    ? null
                    : new YimuCategoryMapper.Mapping(categoryParent, categoryChild);
        }
    }
}
