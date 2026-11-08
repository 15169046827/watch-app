package com.canace.x3offlinereader;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookMediaCache;
import com.canace.x3offlinereader.importer.OpbookImporter;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.playback.CrownVolumePreferences;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class WatchBackupManager implements AutoCloseable {
    private final Context context;
    private final LibraryDb novels;
    private final MusicLibraryDb music;

    WatchBackupManager(Context context) {
        this.context = context.getApplicationContext();
        novels = new LibraryDb(this.context);
        music = new MusicLibraryDb(this.context);
    }

    byte[] exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("product", "X3OfflineReaderWatch");
        root.put("schema", WatchDataPolicy.SCHEMA_VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("warning", "未加密；包含书籍标识、音乐文件路径、进度、收藏和播放设置，不包含源书、音频、歌词或缓存");
        SQLiteDatabase novelDb = novels.getReadableDatabase();
        root.put("novelProgress", query(novelDb,
                "SELECT book_id,chapter_id,track_id,track_position_ms,"
                        + "chapter_position_ms,updated_at FROM chapter_progress",
                "bookId", "chapterId", "trackId", "trackPositionMs",
                "chapterPositionMs", "updatedAt"));
        root.put("novelFavorites", query(novelDb,
                "SELECT book_id,created_at FROM book_favorites", "bookId", "createdAt"));
        root.put("novelSettings", query(novelDb,
                "SELECT key,value FROM book_settings WHERE key='playback_speed'", "key", "value"));
        SQLiteDatabase musicDb = music.getReadableDatabase();
        root.put("musicProgress", query(musicDb,
                "SELECT t.path,p.position_ms,p.updated_at FROM progress p JOIN tracks t ON t.id=p.track_id",
                "path", "positionMs", "updatedAt"));
        root.put("musicFavorites", query(musicDb,
                "SELECT t.path,f.created_at FROM favorites f JOIN tracks t ON t.id=f.track_id",
                "path", "createdAt"));
        root.put("musicSettings", query(musicDb,
                "SELECT key,value FROM settings WHERE key IN ('playback_mode','playback_speed')",
                "key", "value"));
        byte[] result = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (result.length > WatchDataPolicy.MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("备份文件超过 4 MiB 上限");
        }
        return result;
    }

    int importJson(byte[] bytes) throws JSONException {
        if (bytes == null || bytes.length == 0
                || bytes.length > WatchDataPolicy.MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("备份文件大小无效");
        }
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"X3OfflineReaderWatch".equals(root.optString("product"))
                || root.optInt("schema", -1) != WatchDataPolicy.SCHEMA_VERSION) {
            throw new IllegalArgumentException("备份类型或版本不受支持");
        }
        RestoreData data = RestoreData.parse(root);
        restoreNovel(data);
        restoreMusic(data);
        return data.itemCount();
    }

    void clearPlaybackData() {
        stopPlayback();
        SQLiteDatabase novelDb = novels.getWritableDatabase();
        novelDb.beginTransaction();
        try {
            novelDb.delete("book_progress", null, null);
            novelDb.delete("chapter_progress", null, null);
            novelDb.delete("book_favorites", null, null);
            novelDb.delete("book_settings", null, null);
            novelDb.setTransactionSuccessful();
        } finally {
            novelDb.endTransaction();
        }
        SQLiteDatabase musicDb = music.getWritableDatabase();
        musicDb.beginTransaction();
        try {
            musicDb.delete("progress", null, null);
            musicDb.delete("favorites", null, null);
            musicDb.delete("settings", null, null);
            ContentValues speed = new ContentValues();
            speed.put("key", "playback_speed");
            speed.put("value", "1.0");
            musicDb.insert("settings", null, speed);
            ContentValues mode = new ContentValues();
            mode.put("key", "playback_mode");
            mode.put("value", "stop_after_track");
            musicDb.insert("settings", null, mode);
            musicDb.setTransactionSuccessful();
        } finally {
            musicDb.endTransaction();
        }
    }

    void clearNovelLibrary() {
        stopPlayback();
        List<LibraryModels.BookStorage> storage = new ArrayList<>();
        for (LibraryModels.BookSummary book : novels.books()) {
            LibraryModels.BookStorage item = novels.storage(book.id);
            if (item != null) {
                storage.add(item);
            }
        }
        for (LibraryModels.BookStorage item : storage) {
            BookMediaCache.clear(context, item.id);
            deleteAppOwnedIndex(item.rootPath);
            releaseReadGrant(item.sourceUri);
        }
        SQLiteDatabase db = novels.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("book_progress", null, null);
            db.delete("chapter_progress", null, null);
            db.delete("book_favorites", null, null);
            db.delete("tracks", null, null);
            db.delete("chapters", null, null);
            db.delete("volumes", null, null);
            db.delete("books", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void clearMusicLibrary() {
        stopPlayback();
        SQLiteDatabase db = music.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("progress", null, null);
            db.delete("favorites", null, null);
            db.delete("tracks", null, null);
            db.delete("hidden_tracks", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void clearAllLocalData() {
        clearNovelLibrary();
        clearMusicLibrary();
        clearPlaybackData();
        CrownVolumePreferences.clear(context);
        context.getSharedPreferences("data_notice", Context.MODE_PRIVATE).edit().clear().apply();
    }

    private void restoreNovel(RestoreData data) {
        SQLiteDatabase db = novels.getWritableDatabase();
        db.beginTransaction();
        try {
            for (JSONObject item : data.novelProgress) {
                restoreNovelProgress(db, item);
            }
            for (JSONObject item : data.novelFavorites) {
                if (exists(db, "SELECT 1 FROM books WHERE id=?", item.optString("bookId"))) {
                    ContentValues values = new ContentValues();
                    values.put("book_id", item.optString("bookId"));
                    values.put("created_at", item.optLong("createdAt"));
                    db.insertWithOnConflict("book_favorites", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            for (JSONObject item : data.novelSettings) {
                ContentValues values = new ContentValues();
                values.put("key", item.optString("key"));
                values.put("value", item.optString("value"));
                db.insertWithOnConflict("book_settings", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void restoreNovelProgress(SQLiteDatabase db, JSONObject item) {
        String bookId = item.optString("bookId");
        String chapterId = item.optString("chapterId");
        String trackId = item.optString("trackId");
        if (!exists(db,
                "SELECT 1 FROM tracks t JOIN chapters c ON c.id=t.chapter_id "
                        + "JOIN volumes v ON v.id=c.volume_id WHERE v.book_id=? "
                        + "AND c.id=? AND t.id=?",
                bookId, chapterId, trackId)) {
            return;
        }
        long importedAt = item.optLong("updatedAt");
        long currentAt = longValue(db,
                "SELECT updated_at FROM chapter_progress WHERE book_id=? AND chapter_id=?",
                bookId, chapterId);
        if (currentAt > importedAt) {
            return;
        }
        ContentValues progress = new ContentValues();
        progress.put("book_id", bookId);
        progress.put("chapter_id", chapterId);
        progress.put("track_id", trackId);
        progress.put("track_position_ms", item.optLong("trackPositionMs"));
        progress.put("chapter_position_ms", item.optLong("chapterPositionMs"));
        progress.put("updated_at", importedAt);
        db.insertWithOnConflict("chapter_progress", null, progress,
                SQLiteDatabase.CONFLICT_REPLACE);
        long currentBookAt = longValue(db,
                "SELECT updated_at FROM book_progress WHERE book_id=?", bookId);
        if (currentBookAt <= importedAt) {
            ContentValues latest = new ContentValues();
            latest.put("book_id", bookId);
            latest.put("track_id", trackId);
            latest.put("position_ms", item.optLong("trackPositionMs"));
            latest.put("updated_at", importedAt);
            db.insertWithOnConflict("book_progress", null, latest,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void restoreMusic(RestoreData data) {
        SQLiteDatabase db = music.getWritableDatabase();
        db.beginTransaction();
        try {
            for (JSONObject item : data.musicProgress) {
                long trackId = longValue(db, "SELECT id FROM tracks WHERE path=?",
                        item.optString("path"));
                if (trackId <= 0) {
                    continue;
                }
                long importedAt = item.optLong("updatedAt");
                long currentAt = longValue(db,
                        "SELECT updated_at FROM progress WHERE track_id=?",
                        Long.toString(trackId));
                if (currentAt > importedAt) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("track_id", trackId);
                values.put("position_ms", item.optLong("positionMs"));
                values.put("updated_at", importedAt);
                db.insertWithOnConflict("progress", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            for (JSONObject item : data.musicFavorites) {
                long trackId = longValue(db, "SELECT id FROM tracks WHERE path=?",
                        item.optString("path"));
                if (trackId > 0) {
                    ContentValues values = new ContentValues();
                    values.put("track_id", trackId);
                    values.put("created_at", item.optLong("createdAt"));
                    db.insertWithOnConflict("favorites", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            for (JSONObject item : data.musicSettings) {
                ContentValues values = new ContentValues();
                values.put("key", item.optString("key"));
                values.put("value", item.optString("value"));
                db.insertWithOnConflict("settings", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void stopPlayback() {
        PlaybackTargetStore.clear(context);
        context.stopService(new Intent(context, PlaybackService.class));
    }

    private void deleteAppOwnedIndex(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            File target = new File(path).getCanonicalFile();
            for (File base : new File[] {context.getFilesDir(), context.getExternalFilesDir(null)}) {
                if (base != null && target.toPath().startsWith(base.getCanonicalFile().toPath())) {
                    OpbookImporter.delete(target);
                    return;
                }
            }
        } catch (Exception ignored) {
            // The database row is still cleared; never delete outside an app-owned boundary.
        }
    }

    private void releaseReadGrant(String sourceUri) {
        if (sourceUri == null || sourceUri.isEmpty()) {
            return;
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    Uri.parse(sourceUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // The provider may not support persisted grants or the grant may already be gone.
        }
    }

    private static JSONArray query(SQLiteDatabase db, String sql, String... names)
            throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                if (result.length() >= WatchDataPolicy.MAX_ITEMS) {
                    throw new IllegalArgumentException("备份记录数量超过上限");
                }
                JSONObject item = new JSONObject();
                for (int index = 0; index < names.length; index++) {
                    if (cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) {
                        item.put(names[index], cursor.getLong(index));
                    } else {
                        item.put(names[index], cursor.getString(index));
                    }
                }
                result.put(item);
            }
        }
        return result;
    }

    private static boolean exists(SQLiteDatabase db, String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst();
        }
    }

    private static long longValue(SQLiteDatabase db, String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    @Override
    public void close() {
        novels.close();
        music.close();
    }

    private static final class RestoreData {
        final List<JSONObject> novelProgress;
        final List<JSONObject> novelFavorites;
        final List<JSONObject> novelSettings;
        final List<JSONObject> musicProgress;
        final List<JSONObject> musicFavorites;
        final List<JSONObject> musicSettings;

        RestoreData(List<JSONObject> novelProgress, List<JSONObject> novelFavorites,
                    List<JSONObject> novelSettings, List<JSONObject> musicProgress,
                    List<JSONObject> musicFavorites, List<JSONObject> musicSettings) {
            this.novelProgress = novelProgress;
            this.novelFavorites = novelFavorites;
            this.novelSettings = novelSettings;
            this.musicProgress = musicProgress;
            this.musicFavorites = musicFavorites;
            this.musicSettings = musicSettings;
        }

        static RestoreData parse(JSONObject root) throws JSONException {
            RestoreData data = new RestoreData(
                    objects(root, "novelProgress"), objects(root, "novelFavorites"),
                    objects(root, "novelSettings"), objects(root, "musicProgress"),
                    objects(root, "musicFavorites"), objects(root, "musicSettings"));
            data.validate();
            return data;
        }

        int itemCount() {
            return novelProgress.size() + novelFavorites.size() + novelSettings.size()
                    + musicProgress.size() + musicFavorites.size() + musicSettings.size();
        }

        private void validate() {
            if (itemCount() > WatchDataPolicy.MAX_ITEMS) {
                throw new IllegalArgumentException("备份记录数量超过上限");
            }
            validateNovelProgress();
            validateNovelFavorites();
            validateNovelSettings();
            validateMusicProgress();
            validateMusicFavorites();
            validateMusicSettings();
        }

        private void validateNovelProgress() {
            for (JSONObject item : novelProgress) {
                requireId(item, "bookId");
                requireId(item, "chapterId");
                requireId(item, "trackId");
                requireNonNegative(item, "trackPositionMs");
                requireNonNegative(item, "chapterPositionMs");
                requireNonNegative(item, "updatedAt");
            }
        }

        private void validateNovelFavorites() {
            for (JSONObject item : novelFavorites) {
                requireId(item, "bookId");
                requireNonNegative(item, "createdAt");
            }
        }

        private void validateNovelSettings() {
            for (JSONObject item : novelSettings) {
                if (!WatchDataPolicy.validNovelSetting(
                        item.optString("key"), item.optString("value"))) {
                    throw new IllegalArgumentException("小说设置无效");
                }
            }
        }

        private void validateMusicProgress() {
            for (JSONObject item : musicProgress) {
                requirePath(item);
                requireNonNegative(item, "positionMs");
                requireNonNegative(item, "updatedAt");
            }
        }

        private void validateMusicFavorites() {
            for (JSONObject item : musicFavorites) {
                requirePath(item);
                requireNonNegative(item, "createdAt");
            }
        }

        private void validateMusicSettings() {
            for (JSONObject item : musicSettings) {
                if (!WatchDataPolicy.validMusicSetting(
                        item.optString("key"), item.optString("value"))) {
                    throw new IllegalArgumentException("音乐设置无效");
                }
            }
        }

        private static List<JSONObject> objects(JSONObject root, String name)
                throws JSONException {
            JSONArray values = root.getJSONArray(name);
            if (values.length() > WatchDataPolicy.MAX_ITEMS) {
                throw new IllegalArgumentException("备份记录数量超过上限");
            }
            List<JSONObject> result = new ArrayList<>();
            for (int index = 0; index < values.length(); index++) {
                result.add(values.getJSONObject(index));
            }
            return result;
        }

        private static void requireId(JSONObject item, String name) {
            if (!WatchDataPolicy.validId(item.optString(name))) {
                throw new IllegalArgumentException("小说标识无效");
            }
        }

        private static void requirePath(JSONObject item) {
            if (!WatchDataPolicy.validPath(item.optString("path"))) {
                throw new IllegalArgumentException("音乐路径无效");
            }
        }

        private static void requireNonNegative(JSONObject item, String name) {
            if (!item.has(name) || item.optLong(name, -1L) < 0L) {
                throw new IllegalArgumentException("进度或时间无效");
            }
        }
    }
}
