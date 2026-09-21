package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.widget.ListView;
import android.widget.TextView;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookImportCoordinator;
import com.canace.x3offlinereader.ui.ChaptersActivity;
import com.canace.x3offlinereader.ui.PlayerActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** 0.3.0 regression for logical chapters, split Tracks and chapter-level resume. */
public final class WatchLogicalChapterProbeTest extends InstrumentationTestCase {

    private static final String BOOK_ID = "track-a30dda2f160a";

    public void testSplitTracksRemainOneResumableChapter() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        Activity library = null;
        Activity chaptersActivity = null;
        Activity player = null;
        try {
            if (findBook(db) == null) {
                library =
                        getInstrumentation()
                                .startActivitySync(
                                        new Intent(context, BookLibraryActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                File target =
                        new File(
                                library.getExternalFilesDir("test-packages"),
                                "track-split-smoke.opbook");
                copyAsset("track-split-smoke.opbook", target);
                assertTrue(
                        BookImportCoordinator.get()
                                .start(
                                        library,
                                        java.util.Collections.singletonList(
                                                new BookImportCoordinator.Source(
                                                        Uri.fromFile(target), target.getName()))));
                waitFor(() -> findBook(db) != null, 30000);
                library.finish();
                library = null;
            }
            LibraryModels.BookSummary book = findBook(db);
            assertNotNull(book);
            List<LibraryModels.ChapterSummary> chapters = db.chapters(book.id);
            assertEquals(1, chapters.size());
            LibraryModels.ChapterSummary chapter = chapters.get(0);
            assertEquals(2, chapter.tracks.size());
            assertEquals(chapter.tracks.get(0).durationMs, chapter.tracks.get(1).chapterOffsetMs);
            assertEquals(
                    chapter.durationMs,
                    chapter.tracks.get(0).durationMs + chapter.tracks.get(1).durationMs);
            assertEquals("这是第一段用于验证长章节切分的公开短句一二三四五六七八九十。", chapter.title);
            assertFalse(chapter.title.contains("1/2"));
            LibraryModels.TrackSummary second = chapter.tracks.get(1);
            long cumulative = second.chapterOffsetMs + 1234;
            db.saveProgress(book.id, chapter.id, second.id, 1234, cumulative);
            LibraryModels.ResumePoint saved = db.chapterProgress(book.id, chapter.id);
            assertEquals(second.id, saved.trackId);
            assertEquals(cumulative, saved.chapterPositionMs);
            chaptersActivity =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, ChaptersActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("book_id", book.id)
                                            .putExtra("book_title", book.title));
            Activity chapterPage = chaptersActivity;
            waitFor(
                    () -> ((ListView) chapterPage.findViewById(R.id.chapter_list)).getCount() == 1,
                    5000);
            assertTrue(text(chapterPage, R.id.resume_button).contains("续听"));
            assertFalse(
                    ((ListView) chapterPage.findViewById(R.id.chapter_list))
                            .getItemAtPosition(0)
                            .toString()
                            .contains("1/2"));
            chaptersActivity.finish();
            chaptersActivity = null;
            LibraryModels.TrackSummary first = chapter.tracks.get(0);
            long nearEnd = Math.max(0, first.durationMs - 1200);
            db.saveProgress(book.id, chapter.id, first.id, nearEnd, nearEnd);
            player =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, PlayerActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("book_id", book.id)
                                            .putExtra("chapter_id", chapter.id));
            Activity activePlayer = player;
            waitFor(
                    () ->
                            activePlayer.findViewById(R.id.play_button).isEnabled()
                                    && text(activePlayer, R.id.position).contains(" / ")
                                    && text(activePlayer, R.id.full_text).length() > 0,
                    10000);
            assertNull(activePlayer.findViewById(R.id.mode_button));
            assertFalse(text(activePlayer, R.id.title).contains("1/2"));
            CharSequence rendered =
                    ((TextView) activePlayer.findViewById(R.id.full_text)).getText();
            assertTrue(rendered instanceof Spanned);
            assertTrue(
                    ((Spanned) rendered)
                                    .getSpans(0, rendered.length(), BackgroundColorSpan.class)
                                    .length
                            > 0);
            getInstrumentation()
                    .runOnMainSync(
                            () -> activePlayer.findViewById(R.id.play_button).performClick());
            waitFor(
                    () -> {
                        LibraryModels.ResumePoint current = db.latestProgress(book.id);
                        return current != null && second.id.equals(current.trackId);
                    },
                    10000);
            assertEquals(chapter.title, text(activePlayer, R.id.title));
            getInstrumentation()
                    .runOnMainSync(
                            () -> activePlayer.findViewById(R.id.play_button).performClick());
        } finally {
            if (player != null) {
                player.finish();
            }
            if (chaptersActivity != null) {
                chaptersActivity.finish();
            }
            if (library != null) {
                library.finish();
            }
            db.close();
        }
    }

    private static LibraryModels.BookSummary findBook(LibraryDb db) {
        for (LibraryModels.BookSummary book : db.books()) {
            if (BOOK_ID.equals(book.id)) {
                return book;
            }
        }
        return null;
    }

    private static String text(Activity activity, int id) {
        CharSequence value = ((TextView) activity.findViewById(id)).getText();
        return value == null ? "" : value.toString();
    }

    private void copyAsset(String name, File target) throws Exception {
        File parent = target.getParentFile();
        assertTrue(parent.mkdirs() || parent.isDirectory());
        try (InputStream in = getInstrumentation().getContext().getAssets().open(name);
                FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (n > 0) {
                    out.write(buffer, 0, n);
                }
            }
        }
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
        fail("Timed out waiting for device state");
    }

    private interface Check {

        boolean ok() throws Exception;
    }
}
