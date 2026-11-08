package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.ui.PlayerActivity;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Holds real AAC playback long enough for shell-side lock-screen and MediaSession probes. */
public final class WatchBackgroundPlaybackProbeTest extends InstrumentationTestCase {
    public void testRootPlayerSwipeRebuildsLibraryParent() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Instrumentation.ActivityMonitor monitor = getInstrumentation()
                .addMonitor(BookLibraryActivity.class.getName(), null, false);
        Activity player = null;
        Activity library = null;
        LibraryDb db = new LibraryDb(context);
        try {
            GoldenBookFixture.ensureBook(getInstrumentation(), context, db);
            LibraryModels.BookSummary book = goldenBook(db);
            String chapter = db.chapters(book.id).get(0).id;
            player = getInstrumentation().startActivitySync(new Intent(context, PlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra("book_id", book.id).putExtra("track_id", chapter));
            assertTrue(player.isTaskRoot());
            Activity active = player;
            getInstrumentation().runOnMainSync(() -> swipeRight(active));
            library = getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(library);
            assertEquals(player.getTaskId(), library.getTaskId());
            assertFalse(library.isTaskRoot());
        } finally {
            try (LibraryDb fixtureDb = db) {
                getInstrumentation().removeMonitor(monitor);
                GoldenBookFixture.finishActivities(getInstrumentation(), player, library);
                GoldenBookFixture.clean(getInstrumentation(), context, fixtureDb);
            }
        }
    }

    private static void swipeRight(Activity activity) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 110, 300, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 150, MotionEvent.ACTION_UP, 255, 300, 0);
        try {
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    public void testBackgroundPlaybackAndAutomaticTrackTransition() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        String oldSpeed = db.settingValue("playback_speed", null);
        Activity player = null;
        ListenableFuture<MediaController> future = null;
        try {
            GoldenBookFixture.ensureBook(getInstrumentation(), context, db);
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
            try (LibraryDb fixtureDb = db) {
                if (future != null) {
                    ListenableFuture<MediaController> release = future;
                    getInstrumentation().runOnMainSync(() -> MediaController.releaseFuture(release));
                }
                GoldenBookFixture.restorePlaybackSpeed(db, oldSpeed);
                GoldenBookFixture.finishActivities(getInstrumentation(), player);
                GoldenBookFixture.clean(getInstrumentation(), context, fixtureDb);
            }
        }
    }

    private static String text(Activity activity, int id) {
        CharSequence value = ((TextView) activity.findViewById(id)).getText();
        return value == null ? "" : value.toString();
    }

    private static LibraryModels.BookSummary goldenBook(LibraryDb db) {
        for (LibraryModels.BookSummary book : db.books()) {
            if (GoldenBookFixture.BOOK_ID.equals(book.id)) {
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
