package com.canace.x3offlinereader;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookImportCoordinator;
import com.canace.x3offlinereader.importer.BookMediaCache;
import com.canace.x3offlinereader.importer.OpbookImporter;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

final class GoldenBookFixture {
    static final String BOOK_ID = "x3-golden-sample-ebfa1632c722";

    private static final String PACKAGE_NAME = "golden-two-volume.opbook";

    private GoldenBookFixture() { }

    static void ensureBook(Instrumentation instrumentation, Context context, LibraryDb db)
            throws Exception {
        if (db.containsBook(BOOK_ID)) {
            return;
        }
        BookImportCoordinator coordinator = BookImportCoordinator.get();
        awaitImportIdle(coordinator);
        File packageFile = packageFile(context);
        try (InputStream input = instrumentation.getContext().getAssets().open(PACKAGE_NAME);
                FileOutputStream output = new FileOutputStream(packageFile)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1; ) {
                output.write(buffer, 0, count);
            }
        }
        assertTrue(coordinator.start(context, Collections.singletonList(
                new BookImportCoordinator.Source(Uri.fromFile(packageFile), PACKAGE_NAME))));
        awaitImportIdle(coordinator);
        assertTrue("Golden fixture was not indexed: " + coordinator.state().status,
                db.containsBook(BOOK_ID));
    }

    static void finishActivities(Instrumentation instrumentation, Activity... activities) {
        instrumentation.runOnMainSync(() -> {
            for (Activity activity : activities) {
                if (activity != null && !activity.isFinishing()) {
                    activity.finish();
                }
            }
        });
        long deadline = SystemClock.uptimeMillis() + 5_000;
        AtomicBoolean destroyed = new AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> {
                boolean finished = true;
                for (Activity activity : activities) {
                    finished &= activity == null || activity.isDestroyed();
                }
                destroyed.set(finished);
            });
            if (destroyed.get()) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Close fixture activities before removing their data", destroyed.get());
    }

    static void restorePlaybackSpeed(LibraryDb db, String original) {
        if (original == null) {
            db.getWritableDatabase().delete("book_settings", "key=?", new String[] {"playback_speed"});
        } else {
            db.putSetting("playback_speed", original);
        }
    }

    static void clean(Instrumentation instrumentation, Context context, LibraryDb db)
            throws Exception {
        awaitImportIdle(BookImportCoordinator.get());
        File external = context.getExternalFilesDir(null);
        File internalIndex = indexRoot(context.getFilesDir());
        File externalIndex = external == null ? internalIndex : indexRoot(external);
        LibraryModels.BookStorage storage = db.storage(BOOK_ID);
        if (storage != null) {
            File indexed = new File(storage.rootPath).getCanonicalFile();
            assertTrue("Unexpected index location for the golden fixture",
                    internalIndex.equals(indexed) || externalIndex.equals(indexed));
        }
        File packageFile = packageFile(context);
        File cache = BookMediaCache.cacheFile(context, BOOK_ID, "audio/placeholder.m4a")
                .getParentFile().getParentFile();
        assertEquals("Unexpected golden cache path",
                new File(cache.getParentFile().getCanonicalFile(), BOOK_ID), cache.getCanonicalFile());
        db.removeBook(BOOK_ID);
        BookMediaCache.clear(context, BOOK_ID);
        OpbookImporter.delete(internalIndex);
        OpbookImporter.delete(externalIndex);
        assertTrue("Could not delete the exact golden fixture package",
                !packageFile.exists() || packageFile.isFile() && packageFile.delete());
        instrumentation.runOnMainSync(() -> clearPlaybackTarget(context));
        assertFalse(isGoldenPlaybackTarget(context.getSharedPreferences("playback_target", Context.MODE_PRIVATE)));
        assertFalse(db.containsBook(BOOK_ID));
        assertFalse(packageFile.exists());
        assertFalse(cache.exists());
        assertFalse(internalIndex.exists());
        assertFalse(externalIndex.exists());
    }

    private static void clearPlaybackTarget(Context context) {
        SharedPreferences target = context.getSharedPreferences("playback_target", Context.MODE_PRIVATE);
        if (isGoldenPlaybackTarget(target)) {
            PlaybackTargetStore.clear(context);
        }
    }

    private static boolean isGoldenPlaybackTarget(SharedPreferences target) {
        return "book".equals(target.getString("kind", ""))
                && BOOK_ID.equals(target.getString("book_id", ""));
    }

    private static File indexRoot(File base) throws Exception {
        File index = new File(new File(base.getCanonicalFile(), "book-indexes"), BOOK_ID);
        assertEquals("Unexpected golden index path", index, index.getCanonicalFile());
        return index;
    }

    private static File packageFile(Context context) throws Exception {
        File directory = context.getExternalFilesDir("test-packages");
        assertTrue("External fixture directory must be available", directory != null && directory.isDirectory());
        File file = new File(directory.getCanonicalFile(), PACKAGE_NAME);
        assertEquals("Unexpected golden package path", file, file.getCanonicalFile());
        return file;
    }

    private static void awaitImportIdle(BookImportCoordinator coordinator) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (coordinator.state().busy && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50);
        }
        assertFalse("Book import did not finish", coordinator.state().busy);
    }
}
