package com.canace.x3offlinereader;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Exercises migration using only randomized synthetic databases. */
public final class WatchSyntheticBackupTest extends InstrumentationTestCase {
    public void testSyntheticRoundTrip() throws Exception {
        Context real = getInstrumentation().getTargetContext();
        IsolatedContext source = new IsolatedContext(real);
        IsolatedContext target = new IsolatedContext(real);
        IsolatedContext missing = new IsolatedContext(real);
        assertFalse(source.getDatabasePath("offline_reader.db")
                .equals(real.getDatabasePath("offline_reader.db")));
        try {
            seed(source, true);
            seed(target, false);
            try (WatchBackupManager exporter = new WatchBackupManager(source);
                    WatchBackupManager importer = new WatchBackupManager(target);
                    WatchBackupManager unmatched = new WatchBackupManager(missing)) {
                byte[] bytes = exporter.exportJson();
                JSONObject backup = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                assertEquals(1, backup.getJSONArray("novelProgress").length());
                assertEquals(1, backup.getJSONArray("musicProgress").length());
                assertEquals(7, importer.importJson(bytes));
                assertState(target, 120, 330, 1, 1);
                unmatched.importJson(bytes);
                assertState(missing, 0, 0, 0, 0);
                newerProgress(target);
                importer.importJson(bytes);
                assertState(target, 900, 900, 1, 1);
                backup.put("schema", 2);
                try {
                    importer.importJson(backup.toString().getBytes(StandardCharsets.UTF_8));
                    fail("Future schema was accepted");
                } catch (IllegalArgumentException expected) {
                    assertState(target, 900, 900, 1, 1);
                }
            }
        } finally {
            source.cleanup();
            target.cleanup();
            missing.cleanup();
        }
    }

    private static void seed(IsolatedContext context, boolean progress) {
        try (LibraryDb novels = new LibraryDb(context);
                MusicLibraryDb music = new MusicLibraryDb(context)) {
            SQLiteDatabase db = novels.getWritableDatabase();
            db.execSQL("INSERT INTO books VALUES(?,?,?,?,?,?)",
                    new Object[] {"book", "Synthetic", "", "/synthetic/book", "", 1L});
            db.execSQL("INSERT INTO volumes VALUES(?,?,?,?)",
                    new Object[] {"volume", "book", "Volume", 0});
            db.execSQL("INSERT INTO chapters VALUES(?,?,?,?,?)",
                    new Object[] {"chapter", "volume", "Chapter", 0, "/synthetic/text"});
            db.execSQL("INSERT INTO tracks VALUES(?,?,?,?,?,?)",
                    new Object[] {"track", "chapter", 0, "/synthetic/audio",
                        "/synthetic/timeline", 1000});
            music.upsert("/synthetic/song.mp3", "Synthetic", "", "", 1000);
            if (progress) {
                db.execSQL("INSERT INTO chapter_progress VALUES(?,?,?,?,?,?)",
                        new Object[] {"book", "chapter", "track", 120, 120, 10});
                db.execSQL("INSERT INTO book_favorites VALUES(?,?)", new Object[] {"book", 10});
                novels.putSetting("playback_speed", "1.25");
                SQLiteDatabase songs = music.getWritableDatabase();
                songs.execSQL("INSERT INTO progress VALUES((SELECT id FROM tracks WHERE path=?),?,?)",
                        new Object[] {"/synthetic/song.mp3", 330, 10});
                songs.execSQL("INSERT INTO favorites VALUES((SELECT id FROM tracks WHERE path=?),?)",
                        new Object[] {"/synthetic/song.mp3", 10});
                music.putSetting("playback_speed", "1.25");
                music.putSetting("playback_mode", "repeat_one");
            }
        }
    }

    private static void newerProgress(IsolatedContext context) {
        try (LibraryDb novels = new LibraryDb(context);
                MusicLibraryDb music = new MusicLibraryDb(context)) {
            novels.getWritableDatabase().execSQL("UPDATE chapter_progress SET "
                    + "track_position_ms=900,chapter_position_ms=900,updated_at=20");
            music.getWritableDatabase().execSQL(
                    "UPDATE progress SET position_ms=900,updated_at=20");
        }
    }

    private static void assertState(IsolatedContext context, long novel, long song,
            long bookFav, long songFav) {
        try (LibraryDb books = new LibraryDb(context);
                MusicLibraryDb music = new MusicLibraryDb(context)) {
            assertEquals(novel, scalar(books.getReadableDatabase(),
                    "SELECT track_position_ms FROM chapter_progress"));
            assertEquals(song, scalar(music.getReadableDatabase(),
                    "SELECT position_ms FROM progress"));
            assertEquals(bookFav, scalar(books.getReadableDatabase(),
                    "SELECT COUNT(*) FROM book_favorites"));
            assertEquals(songFav, scalar(music.getReadableDatabase(),
                    "SELECT COUNT(*) FROM favorites"));
        }
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final String prefix = "synthetic_backup_"
                + UUID.randomUUID().toString().replace("-", "") + "_";

        IsolatedContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getDatabasePath(String name) {
            return super.getDatabasePath(prefix + name);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return super.openOrCreateDatabase(prefix + name, mode, factory);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return super.openOrCreateDatabase(prefix + name, mode, factory, errorHandler);
        }

        void cleanup() {
            getBaseContext().deleteDatabase(prefix + "offline_reader.db");
            getBaseContext().deleteDatabase(prefix + "music_library.db");
        }
    }
}
