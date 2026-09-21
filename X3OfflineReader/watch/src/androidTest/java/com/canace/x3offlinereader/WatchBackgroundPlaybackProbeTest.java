package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookImportCoordinator;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.ui.PlayerActivity;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Holds real AAC playback long enough for shell-side lock-screen and MediaSession probes. */
public final class WatchBackgroundPlaybackProbeTest extends InstrumentationTestCase {
    public void testBackgroundPlaybackAndAutomaticTrackTransition() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        Activity player = null;
        ListenableFuture<MediaController> future = null;
        try {
            ensureBook(context, db);
            LibraryModels.BookSummary book = goldenBook(db);
            List<LibraryModels.ChapterSummary> chapters = db.chapters(book.id);
            assertTrue(chapters.size() > 1);
            db.putSetting("playback_speed", "1.0");
            db.saveProgress(book.id, chapters.get(0).id, 0);
            Intent intent =
                    new Intent(context, PlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("book_id", book.id)
                            .putExtra("track_id", chapters.get(0).id);
            player = getInstrumentation().startActivitySync(intent);
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.title).length() > 0
                                    && text(active, R.id.position).contains(" / "),
                    20000);
            AtomicReference<ListenableFuture<MediaController>> pending = new AtomicReference<>();
            getInstrumentation()
                    .runOnMainSync(
                            () ->
                                    pending.set(
                                            new MediaController.Builder(
                                                            context,
                                                            new SessionToken(
                                                                    context,
                                                                    new ComponentName(
                                                                            context,
                                                                            PlaybackService.class)))
                                                    .buildAsync()));
            future = pending.get();
            MediaController observer = future.get(10, TimeUnit.SECONDS);
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            waitFor(
                    () -> {
                        boolean[] moved = {false};
                        getInstrumentation()
                                .runOnMainSync(
                                        () -> moved[0] = observer.getCurrentMediaItemIndex() > 0);
                        return moved[0];
                    },
                    20000);
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            boolean saved = false;
            for (LibraryModels.ChapterSummary chapter : chapters) {
                saved |= db.progress(book.id, chapter.id) > 0;
            }
            assertTrue(saved);
        } finally {
            if (future != null) {
                ListenableFuture<MediaController> release = future;
                getInstrumentation().runOnMainSync(() -> MediaController.releaseFuture(release));
            }
            db.putSetting("playback_speed", "1.25");
            if (player != null) {
                Activity closing = player;
                getInstrumentation().runOnMainSync(closing::finish);
                getInstrumentation().waitForIdleSync();
            }
            db.close();
        }
    }

    private static String text(Activity activity, int id) {
        CharSequence value = ((TextView) activity.findViewById(id)).getText();
        return value == null ? "" : value.toString();
    }

    private void ensureBook(Context context, LibraryDb db) throws Exception {
        for (LibraryModels.BookSummary book : db.books()) {
            if ("x3-golden-sample-ebfa1632c722".equals(book.id)) {
                return;
            }
        }
        File target =
                new File(context.getExternalFilesDir("test-packages"), "golden-two-volume.opbook");
        File parent = target.getParentFile();
        assertTrue(parent.mkdirs() || parent.isDirectory());
        try (InputStream input =
                        getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open("golden-two-volume.opbook");
                FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
        }
        assertTrue(
                BookImportCoordinator.get()
                        .start(
                                context,
                                java.util.Collections.singletonList(
                                        new BookImportCoordinator.Source(
                                                Uri.fromFile(target), target.getName()))));
        waitFor(
                () -> {
                    for (LibraryModels.BookSummary book : db.books()) {
                        if ("x3-golden-sample-ebfa1632c722".equals(book.id)) {
                            return true;
                        }
                    }
                    return false;
                },
                20000);
    }

    private static LibraryModels.BookSummary goldenBook(LibraryDb db) {
        for (LibraryModels.BookSummary book : db.books()) {
            if ("x3-golden-sample-ebfa1632c722".equals(book.id)) {
                return book;
            }
        }
        fail("Missing golden book");
        return null;
    }

    private void waitFor(Check check, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            getInstrumentation().waitForIdleSync();
            if (check.ok()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for playback UI");
    }

    private interface Check {
        boolean ok();
    }
}
