package com.canace.x3offlinereader;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.importer.OpbookImporter;
import java.io.File;

/** Re-indexes an already registered large package without logging its private name or URI. */
public final class WatchLargePackageProbeTest extends InstrumentationTestCase {

    public void testLargePersistedPackageIndexesWithinTenSecondsPerGiB() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        try (LibraryDb db = new LibraryDb(context)) {
            Source source = largestRegisteredSource(context, db);
            if (source == null || source.size < 1024L * 1024 * 1024) {
                return;
            }
            long started = android.os.SystemClock.elapsedRealtime();
            expectExistingIdentity(context, db, source.uri);
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            assertEquals(source.size, statSize(context, source.uri));
            assertNoStagingFiles(context);
            long budget = budget(source.size);
            System.out.println(
                    "large-index bytes="
                            + source.size
                            + " elapsedMs="
                            + elapsed
                            + " budgetMs="
                            + budget);
            assertTrue(
                    "Large package index exceeded ten seconds per GiB: " + elapsed + " ms",
                    elapsed <= budget);
        }
    }

    private static Source largestRegisteredSource(Context context, LibraryDb db) throws Exception {
        Source result = null;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            Uri uri = permission.getUri();
            if (!db.containsSource(uri.toString())) {
                continue;
            }
            long size = statSize(context, uri);
            if (result == null || size > result.size) {
                result = new Source(uri, size);
            }
        }
        return result;
    }

    private static long statSize(Context context, Uri uri) throws Exception {
        try (ParcelFileDescriptor descriptor =
                context.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor == null ? -1 : descriptor.getStatSize();
        }
    }

    private static void expectExistingIdentity(Context context, LibraryDb db, Uri source)
            throws Exception {
        try {
            new OpbookImporter(context, db).indexDocument(source);
            fail("Expected existing book identity");
        } catch (OpbookImporter.AlreadyImportedException expected) {
        }
    }

    private static void assertNoStagingFiles(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File[] leftovers = new File(base, "book-index-staging").listFiles();
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    private static long budget(long sourceSize) {
        return Math.max(
                10000L,
                (sourceSize * 10000L + 1024L * 1024 * 1024 - 1)
                        / (1024L * 1024 * 1024));
    }

    private static final class Source {

        final Uri uri;

        final long size;

        Source(Uri uri, long size) {
            this.uri = uri;
            this.size = size;
        }
    }
}
