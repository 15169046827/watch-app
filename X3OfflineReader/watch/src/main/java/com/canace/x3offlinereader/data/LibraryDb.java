package com.canace.x3offlinereader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.canace.x3offlinereader.importer.BookMediaCache;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LibraryDb extends SQLiteOpenHelper {

    private final Context context;

    public LibraryDb(Context context) {
        super(context, "offline_reader.db", null, 4);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT,root_path"
                    + " TEXT NOT NULL,source_uri TEXT NOT NULL DEFAULT '',imported_at INTEGER NOT"
                    + " NULL)");
        db.execSQL(
                "CREATE TABLE volumes(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,title TEXT NOT"
                    + " NULL,sort_order INTEGER NOT NULL,FOREIGN KEY(book_id) REFERENCES books(id)"
                    + " ON DELETE CASCADE)");
        db.execSQL(
                "CREATE TABLE chapters(id TEXT PRIMARY KEY,volume_id TEXT NOT NULL,title TEXT NOT"
                        + " NULL,sort_order INTEGER NOT NULL,content_path TEXT NOT NULL,FOREIGN"
                        + " KEY(volume_id) REFERENCES volumes(id) ON DELETE CASCADE)");
        db.execSQL(
                "CREATE TABLE tracks(id TEXT PRIMARY KEY,chapter_id TEXT NOT NULL,sort_order"
                        + " INTEGER NOT NULL,audio_path TEXT NOT NULL,timeline_path TEXT NOT"
                        + " NULL,duration_ms INTEGER NOT NULL,FOREIGN KEY(chapter_id) REFERENCES"
                        + " chapters(id) ON DELETE CASCADE)");
        db.execSQL(
                "CREATE TABLE book_progress(book_id TEXT PRIMARY KEY,track_id TEXT NOT"
                        + " NULL,position_ms INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        createV2(db);
        createV3(db);
        db.execSQL("CREATE INDEX idx_volumes_book ON volumes(book_id,sort_order)");
        db.execSQL("CREATE INDEX idx_chapters_volume ON chapters(volume_id,sort_order)");
        db.execSQL("CREATE INDEX idx_tracks_chapter ON tracks(chapter_id,sort_order)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createV2(db);
        }
        if (oldVersion < 3) {
            createV3(db);
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE books ADD COLUMN source_uri TEXT NOT NULL DEFAULT ''");
        }
    }

    private static void createV2(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS book_favorites(book_id TEXT PRIMARY KEY,created_at"
                        + " INTEGER NOT NULL,FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE"
                        + " CASCADE)");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS book_settings(key TEXT PRIMARY KEY,value TEXT NOT"
                        + " NULL)");
    }

    private static void createV3(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS chapter_progress(book_id TEXT NOT NULL,chapter_id TEXT"
                    + " NOT NULL,track_id TEXT NOT NULL,track_position_ms INTEGER NOT"
                    + " NULL,chapter_position_ms INTEGER NOT NULL,updated_at INTEGER NOT"
                    + " NULL,PRIMARY KEY(book_id,chapter_id),FOREIGN KEY(book_id) REFERENCES"
                    + " books(id) ON DELETE CASCADE,FOREIGN KEY(chapter_id) REFERENCES chapters(id)"
                    + " ON DELETE CASCADE)");
        db.execSQL(
                "INSERT OR IGNORE INTO"
                    + " chapter_progress(book_id,chapter_id,track_id,track_position_ms,chapter_position_ms,updated_at)"
                    + " SELECT"
                    + " bp.book_id,t.chapter_id,bp.track_id,bp.position_ms,bp.position_ms+COALESCE((SELECT"
                    + " SUM(prior.duration_ms) FROM tracks prior WHERE"
                    + " prior.chapter_id=t.chapter_id AND"
                    + " prior.sort_order<t.sort_order),0),bp.updated_at FROM book_progress bp JOIN"
                    + " tracks t ON t.id=bp.track_id");
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_chapter_progress_latest ON"
                        + " chapter_progress(book_id,updated_at DESC)");
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    public void insertBook(LibraryModels.Book book) {
        insertBook(book, "");
    }

    public void insertBook(LibraryModels.Book book, String sourceUri) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("id", book.id);
            values.put("title", book.title);
            values.put("author", book.author);
            values.put("root_path", book.rootPath);
            values.put("source_uri", sourceUri == null ? "" : sourceUri);
            values.put("imported_at", System.currentTimeMillis());
            db.insertOrThrow("books", null, values);
            for (LibraryModels.Volume volume : book.volumes) {
                String volumeId = scoped(book.id, volume.id);
                values = new ContentValues();
                values.put("id", volumeId);
                values.put("book_id", book.id);
                values.put("title", volume.title);
                values.put("sort_order", volume.order);
                db.insertOrThrow("volumes", null, values);
                for (LibraryModels.Chapter chapter : volume.chapters) {
                    String chapterId = scoped(book.id, chapter.id);
                    values = new ContentValues();
                    values.put("id", chapterId);
                    values.put("volume_id", volumeId);
                    values.put("title", chapter.title);
                    values.put("sort_order", chapter.order);
                    values.put("content_path", chapter.contentPath);
                    db.insertOrThrow("chapters", null, values);
                    for (LibraryModels.Track track : chapter.tracks) {
                        values = new ContentValues();
                        values.put("id", scoped(book.id, track.id));
                        values.put("chapter_id", chapterId);
                        values.put("sort_order", track.order);
                        values.put("audio_path", track.audioPath);
                        values.put("timeline_path", track.timelinePath);
                        values.put("duration_ms", track.durationMs);
                        db.insertOrThrow("tracks", null, values);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<LibraryModels.BookSummary> books() {
        List<LibraryModels.BookSummary> result = new ArrayList<>();
        String sql =
                "SELECT b.id,b.title,b.author,b.source_uri,CASE WHEN f.book_id IS NULL THEN 0 ELSE"
                    + " 1 END FROM books b LEFT JOIN book_favorites f ON f.book_id=b.id LEFT JOIN"
                    + " (SELECT book_id,MAX(updated_at) AS last_played FROM chapter_progress GROUP"
                    + " BY book_id) p ON p.book_id=b.id ORDER BY CASE WHEN p.last_played IS NULL"
                    + " THEN 1 ELSE 0 END,p.last_played DESC,b.imported_at DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                result.add(
                        new LibraryModels.BookSummary(
                                c.getString(0),
                                c.getString(1),
                                c.getString(2),
                                c.getString(3),
                                c.getInt(4) != 0));
            }
        }
        return result;
    }

    public List<LibraryModels.ChapterSummary> chapters(String bookId) {
        List<LibraryModels.ChapterSummary> result = new ArrayList<>();
        String sql =
                "SELECT c.id,c.title,b.root_path||'/'||c.content_path,t.id,t.audio_path,"
                    + "b.root_path||'/'||t.timeline_path,t.duration_ms,b.source_uri,b.root_path"
                    + " FROM books b JOIN volumes v ON v.book_id=b.id JOIN chapters c ON"
                    + " c.volume_id=v.id JOIN tracks t ON t.chapter_id=c.id WHERE b.id=? ORDER BY"
                    + " v.sort_order,c.sort_order,t.sort_order";
        String chapterId = null;
        String title = null;
        String contentPath = null;
        long total = 0;
        List<LibraryModels.TrackSummary> tracks = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[] {bookId})) {
            while (c.moveToNext()) {
                String nextId = c.getString(0);
                if (chapterId != null && !chapterId.equals(nextId)) {
                    result.add(
                            new LibraryModels.ChapterSummary(
                                    chapterId, title, contentPath, total, tracks));
                    tracks = new ArrayList<>();
                    total = 0;
                }
                chapterId = nextId;
                contentPath = c.getString(2);
                title = c.getString(1);
                long duration = c.getLong(6);
                String sourceUri = c.getString(7);
                String entry = c.getString(4);
                String audioPath =
                        sourceUri == null || sourceUri.isEmpty()
                                ? new File(c.getString(8), entry).getAbsolutePath()
                                : BookMediaCache.cacheFile(context, bookId, entry)
                                        .getAbsolutePath();
                tracks.add(
                        new LibraryModels.TrackSummary(
                                c.getString(3),
                                audioPath,
                                entry,
                                c.getString(5),
                                sourceUri,
                                duration,
                                total));
                total += duration;
            }
        }
        if (chapterId != null) {
            result.add(
                    new LibraryModels.ChapterSummary(chapterId, title, contentPath, total, tracks));
        }
        return result;
    }

    public void saveProgress(
            String bookId,
            String chapterId,
            String trackId,
            long trackPositionMs,
            long chapterPositionMs) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("book_id", bookId);
        v.put("chapter_id", chapterId);
        v.put("track_id", trackId);
        v.put("track_position_ms", Math.max(0, trackPositionMs));
        v.put("chapter_position_ms", Math.max(0, chapterPositionMs));
        v.put("updated_at", now);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c =
                    db.rawQuery(
                            "SELECT 1 FROM tracks t JOIN chapters c ON c.id=t.chapter_id JOIN"
                                + " volumes v ON v.id=c.volume_id WHERE v.book_id=? AND c.id=? AND"
                                + " t.id=?",
                            new String[] {bookId, chapterId, trackId})) {
                if (!c.moveToFirst()) {
                    return;
                }
            }
            db.insertWithOnConflict("chapter_progress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues latest = new ContentValues();
            latest.put("book_id", bookId);
            latest.put("track_id", trackId);
            latest.put("position_ms", Math.max(0, trackPositionMs));
            latest.put("updated_at", now);
            db.insertWithOnConflict("book_progress", null, latest, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public LibraryModels.ResumePoint chapterProgress(String bookId, String chapterId) {
        return resume(
                "SELECT chapter_id,track_id,track_position_ms,chapter_position_ms,updated_at FROM"
                        + " chapter_progress WHERE book_id=? AND chapter_id=?",
                new String[] {bookId, chapterId});
    }

    public LibraryModels.ResumePoint latestProgress(String bookId) {
        return resume(
                "SELECT chapter_id,track_id,track_position_ms,chapter_position_ms,updated_at FROM"
                        + " chapter_progress WHERE book_id=? ORDER BY updated_at DESC LIMIT 1",
                new String[] {bookId});
    }

    private LibraryModels.ResumePoint resume(String sql, String[] args) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            return c.moveToFirst()
                    ? new LibraryModels.ResumePoint(
                            c.getString(0),
                            c.getString(1),
                            c.getLong(2),
                            c.getLong(3),
                            c.getLong(4))
                    : null;
        }
    }

    public long progress(String bookId, String trackId) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT CASE WHEN chapter_id=? THEN chapter_position_ms ELSE"
                                    + " track_position_ms END FROM chapter_progress WHERE book_id=?"
                                    + " AND (track_id=? OR chapter_id=?) ORDER BY updated_at DESC"
                                    + " LIMIT 1",
                                new String[] {trackId, bookId, trackId, trackId})) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    /** Compatibility helper for older probes; the identifier may be a chapter or track. */
    public void saveProgress(String bookId, String identifier, long positionMs) {
        String sql =
                "SELECT c.id,t.id FROM chapters c JOIN volumes v ON v.id=c.volume_id JOIN tracks t"
                        + " ON t.chapter_id=c.id WHERE v.book_id=? AND (c.id=? OR t.id=?) ORDER BY"
                        + " t.sort_order LIMIT 1";
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(sql, new String[] {bookId, identifier, identifier})) {
            if (c.moveToFirst()) {
                saveProgress(bookId, c.getString(0), c.getString(1), positionMs, positionMs);
            }
        }
    }

    public void toggleFavorite(String bookId) {
        SQLiteDatabase db = getWritableDatabase();
        if (db.delete("book_favorites", "book_id=?", new String[] {bookId}) == 0) {
            ContentValues v = new ContentValues();
            v.put("book_id", bookId);
            v.put("created_at", System.currentTimeMillis());
            db.insert("book_favorites", null, v);
        }
    }

    public boolean isFavorite(String bookId) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT 1 FROM book_favorites WHERE book_id=?",
                                new String[] {bookId})) {
            return c.moveToFirst();
        }
    }

    public String settingValue(String key, String fallback) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT value FROM book_settings WHERE key=?",
                                new String[] {key})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }

    public void putSetting(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase()
                .insertWithOnConflict("book_settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean containsBook(String bookId) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT 1 FROM books WHERE id=?", new String[] {bookId})) {
            return c.moveToFirst();
        }
    }

    public boolean containsSource(String sourceUri) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT 1 FROM books WHERE source_uri=?",
                                new String[] {sourceUri})) {
            return c.moveToFirst();
        }
    }

    public LibraryModels.BookStorage storage(String bookId) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT id,title,root_path,source_uri FROM books WHERE id=?",
                                new String[] {bookId})) {
            return c.moveToFirst()
                    ? new LibraryModels.BookStorage(
                            c.getString(0), c.getString(1), c.getString(2), c.getString(3))
                    : null;
        }
    }

    public void removeBook(String bookId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("book_progress", "book_id=?", new String[] {bookId});
            db.delete("chapter_progress", "book_id=?", new String[] {bookId});
            db.delete("book_favorites", "book_id=?", new String[] {bookId});
            db.delete("books", "id=?", new String[] {bookId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String scoped(String bookId, String localId) {
        return bookId + ":" + localId;
    }

    private static String displayTitle(String contentPath, String fallback) {
        try {
            JSONObject root =
                    new JSONObject(
                            new String(
                                    Files.readAllBytes(new File(contentPath).toPath()),
                                    StandardCharsets.UTF_8));
            JSONArray blocks = root.getJSONArray("blocks");
            String first = null;
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.getJSONObject(i);
                String value = block.getString("text").trim().replaceAll("\\s+", " ");
                if (value.isEmpty()) {
                    continue;
                }
                if (first == null) {
                    first = value;
                }
                if ("paragraph".equals(block.optString("type"))) {
                    return value;
                }
            }
            if (first != null) {
                return first;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
