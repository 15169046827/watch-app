package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Instrumentation.ActivityMonitor;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.OpbookImporter;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.canace.x3offlinereader.ui.BookSearchActivity;
import com.canace.x3offlinereader.ui.ChaptersActivity;
import com.canace.x3offlinereader.ui.PlayerActivity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Self-contained indexed-text search, round-layout and exact-navigation probe. */
public final class WatchBookSearchProbeTest extends InstrumentationTestCase {

    private static final String BOOK_ID = "book-search-public-probe";

    public void testSearchAcrossSplitTracksAndOpenResult() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        File root = new File(context.getCacheDir(), BOOK_ID);
        Activity chapters = null;
        Activity searchPage = null;
        Activity player = null;
        ActivityMonitor searchMonitor = null;
        ActivityMonitor playerMonitor = null;
        try {
            db.removeBook(BOOK_ID);
            OpbookImporter.delete(root);
            LibraryModels.Book book = book(root);
            db.insertBook(book);
            assertEngine(db.chapters(BOOK_ID));
            searchMonitor =
                    getInstrumentation()
                            .addMonitor(BookSearchActivity.class.getName(), null, false);
            chapters =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, ChaptersActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("book_id", BOOK_ID)
                                            .putExtra("book_title", book.title));
            Activity activeChapters = chapters;
            waitFor(() -> activeChapters.findViewById(R.id.search_button).getWidth() > 0, 3000);
            assertInsideDisplay(activeChapters.findViewById(R.id.search_button));
            getInstrumentation()
                    .runOnMainSync(
                            () -> activeChapters.findViewById(R.id.search_button).performClick());
            searchPage = getInstrumentation().waitForMonitorWithTimeout(searchMonitor, 3000);
            assertNotNull(searchPage);
            Activity activeSearch = searchPage;
            EditText query = activeSearch.findViewById(R.id.search_query);
            View submit = activeSearch.findViewById(R.id.search_submit);
            boolean[] clicked = {false};
            getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                query.setText("目标");
                                clicked[0] = submit.performClick();
                            });
            assertTrue(clicked[0]);
            ListView list = activeSearch.findViewById(R.id.search_results);
            waitFor(() -> list.getCount() == 1, 5000);
            assertInsideDisplay(query);
            assertInsideDisplay(submit);
            assertInsideDisplay(list);
            assertTrue(list.getItemAtPosition(0).toString().startsWith("第一章 · 00:10"));
            assertTrue(list.getItemAtPosition(0).toString().contains("目标内容"));
            View row = list.getAdapter().getView(0, null, list);
            row.measure(
                    View.MeasureSpec.makeMeasureSpec(list.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(row.getMeasuredWidth() <= list.getWidth());
            assertTrue(row.getMeasuredHeight() <= list.getHeight());
            playerMonitor =
                    getInstrumentation().addMonitor(PlayerActivity.class.getName(), null, false);
            getInstrumentation()
                    .runOnMainSync(
                            () ->
                                    list.getOnItemClickListener()
                                            .onItemClick(
                                                    list, null, 0, list.getItemIdAtPosition(0)));
            player = getInstrumentation().waitForMonitorWithTimeout(playerMonitor, 3000);
            assertNotNull(player);
            assertEquals(BOOK_ID + ":track-2", player.getIntent().getStringExtra("start_track_id"));
            assertEquals(500, player.getIntent().getLongExtra("start_position_ms", -1));
        } finally {
            if (player != null) {
                player.finish();
            }
            if (searchPage != null) {
                searchPage.finish();
            }
            if (chapters != null) {
                chapters.finish();
            }
            if (searchMonitor != null) {
                getInstrumentation().removeMonitor(searchMonitor);
            }
            if (playerMonitor != null) {
                getInstrumentation().removeMonitor(playerMonitor);
            }
            context.stopService(new Intent(context, PlaybackService.class));
            PlaybackTargetStore.clear(context);
            db.removeBook(BOOK_ID);
            db.close();
            OpbookImporter.delete(root);
        }
    }

    private static LibraryModels.Book book(File root) throws Exception {
        assertTrue(root.mkdirs() || root.isDirectory());
        File content = new File(root, "content/chapter.json");
        File first = new File(root, "timeline/track-1.json");
        File second = new File(root, "timeline/track-2.json");
        String text = "第一句 Hello 世界。第二句 目标内容。";
        String block = "block";
        int split = text.indexOf("第二句");
        JSONObject contentJson =
                new JSONObject()
                        .put(
                                "blocks",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("blockId", block)
                                                        .put("text", text)));
        write(content, contentJson);
        write(first, timeline(block, 0, split, text.substring(0, split), 100));
        write(
                second,
                timeline(
                        block,
                        split,
                        text.codePointCount(0, text.length()),
                        text.substring(split),
                        500));
        LibraryModels.Book book = new LibraryModels.Book();
        book.id = BOOK_ID;
        book.title = "公开搜索测试";
        book.author = "Codex";
        book.rootPath = root.getAbsolutePath();
        LibraryModels.Volume volume = new LibraryModels.Volume();
        volume.id = "volume";
        volume.title = "第一卷";
        volume.order = 0;
        LibraryModels.Chapter chapter = new LibraryModels.Chapter();
        chapter.id = "chapter";
        chapter.title = "第一章";
        chapter.order = 0;
        chapter.contentPath = "content/chapter.json";
        chapter.tracks.add(track("track-1", 0, 10000));
        chapter.tracks.add(track("track-2", 1, 10000));
        volume.chapters.add(chapter);
        book.volumes.add(volume);
        return book;
    }

    private static JSONObject timeline(String block, int start, int end, String text, long startMs)
            throws Exception {
        return new JSONObject()
                .put(
                        "segments",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("blockId", block)
                                                .put("startChar", start)
                                                .put("endChar", end)
                                                .put("startMs", startMs)
                                                .put("endMs", startMs + 1000)
                                                .put("text", text)));
    }

    private static LibraryModels.Track track(String id, int order, long duration) {
        LibraryModels.Track track = new LibraryModels.Track();
        track.id = id;
        track.order = order;
        track.audioPath = "audio/" + id + ".m4a";
        track.timelinePath = "timeline/" + id + ".json";
        track.durationMs = duration;
        return track;
    }

    private static void assertEngine(java.util.List<LibraryModels.ChapterSummary> chapters)
            throws Exception {
        Class<?> type = Class.forName("com.canace.x3offlinereader.ui.BookSearchEngine");
        java.lang.reflect.Method method =
                type.getDeclaredMethod("search", java.util.List.class, String.class);
        method.setAccessible(true);
        Object outcome;
        try {
            outcome = method.invoke(null, chapters, "目标");
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        }
        java.lang.reflect.Field field = outcome.getClass().getDeclaredField("results");
        field.setAccessible(true);
        assertEquals(1, ((java.util.List<?>) field.get(outcome)).size());
    }

    private static void write(File file, JSONObject value) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.mkdirs() || parent.isDirectory());
        Files.write(file.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInsideDisplay(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        assertTrue(view.getVisibility() == View.VISIBLE);
        assertTrue(at[0] >= 0 && at[1] >= 0);
        assertTrue(at[0] + view.getWidth() <= 466);
        assertTrue(at[1] + view.getHeight() <= 466);
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
        fail("Timed out waiting for book search UI");
    }

    private interface Check {

        boolean ok() throws Exception;
    }
}
