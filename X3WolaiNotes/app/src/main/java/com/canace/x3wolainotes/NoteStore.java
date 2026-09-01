package com.canace.x3wolainotes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class NoteStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "x3_wolai_notes.db";
    private static final int DATABASE_VERSION = 4;
    static final int MAX_RECENT_PAGES = 10;

    NoteStore(Context context) {
        this(context, DATABASE_NAME);
    }

    NoteStore(Context context, String databaseName) {
        super(context, databaseName, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_notes (" +
                "id TEXT PRIMARY KEY," +
                "text TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "state INTEGER NOT NULL," +
                "error TEXT NOT NULL DEFAULT ''," +
                "last_attempt_at INTEGER NOT NULL DEFAULT 0)");
        createRecentPagesTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("DROP TABLE IF EXISTS remote_cache");
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE local_notes ADD COLUMN last_attempt_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) createRecentPagesTable(db);
    }

    void insertPending(NoteItem item) {
        ContentValues values = new ContentValues();
        values.put("id", item.id);
        values.put("text", item.text);
        values.put("kind", item.kind);
        values.put("created_at", item.createdAt);
        values.put("state", NoteItem.STATE_PENDING);
        values.put("error", "");
        values.put("last_attempt_at", 0L);
        getWritableDatabase().insertOrThrow("local_notes", null, values);
    }

    void markAttempting(String id, long attemptedAt) {
        ContentValues values = new ContentValues();
        // Persist the uncertain state before the network write. If the process is interrupted
        // after Wolai accepts the request, the next launch reconciles instead of posting again.
        values.put("state", NoteItem.STATE_UNCERTAIN);
        values.put("error", "");
        values.put("last_attempt_at", attemptedAt);
        getWritableDatabase().update("local_notes", values, "id = ?", new String[]{id});
    }

    void markSynced(String id) {
        ContentValues values = new ContentValues();
        values.put("state", NoteItem.STATE_SYNCED);
        values.put("error", "");
        getWritableDatabase().update("local_notes", values, "id = ?", new String[]{id});
    }

    void markFailed(String id, String error) {
        ContentValues values = new ContentValues();
        values.put("state", NoteItem.STATE_FAILED);
        values.put("error", safeError(error));
        getWritableDatabase().update("local_notes", values, "id = ?", new String[]{id});
    }

    void markUncertain(String id, String error) {
        ContentValues values = new ContentValues();
        values.put("state", NoteItem.STATE_UNCERTAIN);
        values.put("error", safeError(error));
        getWritableDatabase().update("local_notes", values, "id = ?", new String[]{id});
    }

    List<NoteItem> pending(int limit) {
        return queryLocal("state != ?", new String[]{String.valueOf(NoteItem.STATE_SYNCED)},
                "created_at ASC", limit);
    }

    List<NoteItem> recentLocal(int limit) {
        return queryLocal(null, null, "created_at DESC", limit);
    }

    void recordRecentPage(String pageId, String title, long accessedAt) {
        String safeId = safeValue(pageId, 200);
        if (safeId.isEmpty()) return;
        String safeTitle = safeValue(title, 240);
        if (safeTitle.isEmpty()) safeTitle = "未命名页面";
        ContentValues values = new ContentValues();
        values.put("page_id", safeId);
        values.put("title", safeTitle);
        values.put("accessed_at", Math.max(0L, accessedAt));
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("recent_pages", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM recent_pages WHERE page_id NOT IN (" +
                "SELECT page_id FROM recent_pages " +
                "ORDER BY accessed_at DESC, rowid DESC LIMIT " + MAX_RECENT_PAGES + ")");
    }

    List<RecentPage> recentPages(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_RECENT_PAGES, limit));
        List<RecentPage> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("recent_pages",
                new String[]{"page_id", "title", "accessed_at"},
                null, null, null, null, "accessed_at DESC, rowid DESC",
                String.valueOf(safeLimit))) {
            while (cursor.moveToNext()) {
                result.add(new RecentPage(cursor.getString(0), cursor.getString(1),
                        cursor.getLong(2)));
            }
        }
        return result;
    }

    void clearRecentPages() {
        getWritableDatabase().delete("recent_pages", null, null);
    }

    int pendingCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM local_notes WHERE state != ?",
                new String[]{String.valueOf(NoteItem.STATE_SYNCED)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    int uncertainCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM local_notes WHERE state = ?",
                new String[]{String.valueOf(NoteItem.STATE_UNCERTAIN)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private List<NoteItem> queryLocal(String selection, String[] args, String order, int limit) {
        List<NoteItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("local_notes",
                new String[]{"id", "text", "kind", "created_at", "state", "error",
                        "last_attempt_at"},
                selection, args, null, null, order, String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                result.add(new NoteItem(cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getLong(3), cursor.getInt(4), cursor.getString(5),
                        cursor.getLong(6)));
            }
        }
        return result;
    }

    private static String safeError(String error) {
        if (error == null) return "同步失败";
        String value = error.trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static void createRecentPagesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recent_pages (" +
                "page_id TEXT PRIMARY KEY," +
                "title TEXT NOT NULL," +
                "accessed_at INTEGER NOT NULL)");
    }

    private static String safeValue(String value, int maxLength) {
        if (value == null) return "";
        String safe = value.trim();
        return safe.length() > maxLength ? safe.substring(0, maxLength) : safe;
    }
}
