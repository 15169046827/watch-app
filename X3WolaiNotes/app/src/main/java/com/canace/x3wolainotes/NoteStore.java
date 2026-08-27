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
    private static final int DATABASE_VERSION = 2;

    NoteStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_notes (" +
                "id TEXT PRIMARY KEY," +
                "text TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "state INTEGER NOT NULL," +
                "error TEXT NOT NULL DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("DROP TABLE IF EXISTS remote_cache");
    }

    void insertPending(NoteItem item) {
        ContentValues values = new ContentValues();
        values.put("id", item.id);
        values.put("text", item.text);
        values.put("kind", item.kind);
        values.put("created_at", item.createdAt);
        values.put("state", NoteItem.STATE_PENDING);
        values.put("error", "");
        getWritableDatabase().insertOrThrow("local_notes", null, values);
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

    List<NoteItem> pending(int limit) {
        return queryLocal("state != ?", new String[]{String.valueOf(NoteItem.STATE_SYNCED)},
                "created_at ASC", limit);
    }

    List<NoteItem> recentLocal(int limit) {
        return queryLocal(null, null, "created_at DESC", limit);
    }

    int pendingCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM local_notes WHERE state != ?",
                new String[]{String.valueOf(NoteItem.STATE_SYNCED)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private List<NoteItem> queryLocal(String selection, String[] args, String order, int limit) {
        List<NoteItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("local_notes",
                new String[]{"id", "text", "kind", "created_at", "state", "error"},
                selection, args, null, null, order, String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                result.add(new NoteItem(cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getLong(3), cursor.getInt(4), cursor.getString(5)));
            }
        }
        return result;
    }

    private static String safeError(String error) {
        if (error == null) return "同步失败";
        String value = error.trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
