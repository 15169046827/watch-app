package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookImportCoordinator;
import com.canace.x3offlinereader.importer.BookMediaCache;
import com.canace.x3offlinereader.importer.OpbookImporter;
import com.canace.x3offlinereader.music.MusicLibraryActivity;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.music.MusicScanner;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.ui.ChapterLabels;
import com.canace.x3offlinereader.ui.ChaptersActivity;
import com.canace.x3offlinereader.ui.HorizontalPager;
import com.canace.x3offlinereader.ui.PageIndicatorView;
import com.canace.x3offlinereader.ui.PlayerActivity;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import com.canace.x3offlinereader.ui.RoundSpeedDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Framework-only smoke coverage that can run on the API 30 target without extra test libraries. */
public final class WatchDeviceSmokeTest extends ActivityInstrumentationTestCase2<MainActivity> {

    private static final long TIMEOUT_MS = 20000;

    public WatchDeviceSmokeTest() {
        super(MainActivity.class);
    }

    public void test01ImportAndRoundSafeHome() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        SharedPreferences target = context.getSharedPreferences("playback_target", 0);
        Map<String, ?> savedTarget = target.getAll();
        target.edit().clear().commit();
        MainActivity activity = getActivity();
        restore(target, savedTarget);
        assertInsideDisplay(activity.findViewById(R.id.books_button));
        assertInsideDisplay(activity.findViewById(R.id.music_button));
        assertEquals("小说", text(activity, R.id.books_button));
        assertEquals("音乐", text(activity, R.id.music_button));
        int[] booksAt = new int[2];
        int[] musicAt = new int[2];
        activity.findViewById(R.id.books_button).getLocationOnScreen(booksAt);
        activity.findViewById(R.id.music_button).getLocationOnScreen(musicAt);
        assertEquals(booksAt[1], musicAt[1]);
        assertTrue(booksAt[0] < musicAt[0]);
        Activity library =
                getInstrumentation()
                        .startActivitySync(
                                new Intent(activity, BookLibraryActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        LibraryDb db = new LibraryDb(activity);
        try {
            assertInsideDisplay(library.findViewById(R.id.choose_button));
            assertTrue(library.findViewById(R.id.book_list).getHeight() >= 180);
            assertTrue(library.findViewById(R.id.book_list).getWidth() >= 360);
            ensureBook(library, db);
            List<LibraryModels.BookSummary> books = db.books();
            assertFalse(books.isEmpty());
            LibraryModels.BookSummary golden = goldenBook(db);
            for (LibraryModels.BookSummary book : books) {
                assertFalse(book.toString().startsWith("★"));
            }
            List<LibraryModels.ChapterSummary> chapters = db.chapters(golden.id);
            assertEquals(3, chapters.size());
            for (LibraryModels.ChapterSummary chapter : chapters) {
                assertTrue(new File(chapter.contentPath).isFile());
                assertFalse(chapter.tracks.isEmpty());
                for (LibraryModels.TrackSummary track : chapter.tracks) {
                    assertTrue(track.audioEntryPath.startsWith("audio/"));
                    assertTrue(new File(track.timelinePath).isFile());
                }
            }
            waitFor(
                    () ->
                            books.size()
                                    == ((ListView) library.findViewById(R.id.book_list)).getCount(),
                    TIMEOUT_MS);
            assertEquals(
                    books.size(), ((ListView) library.findViewById(R.id.book_list)).getCount());
            final int goldenIndex = findBookIndex(books, golden.id);
            assertTrue(goldenIndex >= 0);
            android.app.Instrumentation.ActivityMonitor monitor =
                    getInstrumentation().addMonitor(PlayerActivity.class.getName(), null, false);
            ListView bookList = library.findViewById(R.id.book_list);
            getInstrumentation()
                    .runOnMainSync(
                            () ->
                                    bookList.performItemClick(
                                            null,
                                            goldenIndex,
                                            bookList.getAdapter().getItemId(goldenIndex)));
            Activity direct = getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(direct);
            assertTrue(direct.getIntent().getBooleanExtra("resume_latest", false));
            getInstrumentation().runOnMainSync(direct::finish);
            getInstrumentation().removeMonitor(monitor);
        } finally {
            library.finish();
            db.close();
        }
    }

    public void test02BookPlaybackTimelineAndSettings() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        Activity player = null;
        try {
            if (db.books().isEmpty()) {
                Activity library =
                        getInstrumentation()
                                .startActivitySync(
                                        new Intent(context, BookLibraryActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    ensureBook(library, db);
                } finally {
                    library.finish();
                }
            }
            LibraryModels.BookSummary book = goldenBook(db);
            List<LibraryModels.ChapterSummary> chapters = db.chapters(book.id);
            assertFalse(chapters.isEmpty());
            db.putSetting("playback_speed", "1.25");
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
                            text(active, R.id.title).length() > 0
                                    && text(active, R.id.full_text).length() > 0,
                    TIMEOUT_MS);
            HorizontalPager pager = active.findViewById(R.id.player_pager);
            assertEquals(0, pager.currentPage());
            int[] controls = {
                R.id.previous_button,
                R.id.play_button,
                R.id.next_button,
                R.id.directory_button,
                R.id.speed_button,
                R.id.volume_button,
                R.id.output_button
            };
            for (int id : controls) {
                View control = active.findViewById(id);
                assertInsideDisplay(control);
                assertTrue(control instanceof ImageButton);
                assertNotNull(((ImageButton) control).getDrawable());
                assertTrue(description(active, id).length() > 0);
                assertStableIconState((ImageButton) control);
            }
            ImageButton primary = active.findViewById(R.id.play_button);
            assertMinimumTouchHeight(primary);
            assertEquals(
                    Math.round(74 * primary.getResources().getDisplayMetrics().density),
                    primary.getWidth());
            assertEquals(
                    Math.round(78 * primary.getResources().getDisplayMetrics().density),
                    ((View) primary.getParent()).getWidth());
            assertTrue(primary.getWidth() > active.findViewById(R.id.previous_button).getWidth());
            assertTrue(
                    active.findViewById(R.id.previous_button).getWidth()
                            > active.findViewById(R.id.directory_button).getWidth());
            assertSameRow(
                    active,
                    R.id.directory_button,
                    R.id.speed_button,
                    R.id.volume_button,
                    R.id.output_button);
            assertNull(active.findViewById(R.id.back_button));
            assertNull(active.findViewById(R.id.forward_button));
            assertNull(active.findViewById(R.id.favorite_button));
            assertEquals(
                    View.GONE, active.findViewById(R.id.play_from_here_button).getVisibility());
            assertEquals("倍速 1.3×", description(active, R.id.speed_button));
            assertTrue(text(active, R.id.position).contains(" / "));
            getInstrumentation().runOnMainSync(() -> pager.setCurrentPage(1, false));
            getInstrumentation().waitForIdleSync();
            assertEquals("正文页，第 2 页，共 2 页", description(active, R.id.page_indicator));
            assertInsideDisplay(active.findViewById(R.id.reader_scroll));
            assertTrue(text(active, R.id.full_text).length() > 0);
            ScrollView scroll = active.findViewById(R.id.reader_scroll);
            TextView reader = active.findViewById(R.id.full_text);
            assertFullScreenNativeRoundScrollbar(active, scroll);
            assertEquals(active.findViewById(R.id.content_page).getBottom(), scroll.getBottom());
            if (scroll.getChildAt(0).getHeight() <= scroll.getHeight() + 4) {
                getInstrumentation().runOnMainSync(() -> reader.append("\n\n" + reader.getText()));
            }
            getInstrumentation().waitForIdleSync();
            int maxScroll = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
            assertTrue("Expanded golden text must be scrollable", maxScroll > 4);
            long touch = SystemClock.uptimeMillis();
            getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                scroll.dispatchTouchEvent(
                                        MotionEvent.obtain(
                                                touch,
                                                touch,
                                                MotionEvent.ACTION_DOWN,
                                                120,
                                                160,
                                                0));
                                scroll.scrollTo(0, Math.min(maxScroll, 80));
                                scroll.dispatchTouchEvent(
                                        MotionEvent.obtain(
                                                touch,
                                                touch + 120,
                                                MotionEvent.ACTION_UP,
                                                120,
                                                80,
                                                0));
                            });
            waitFor(
                    () ->
                            active.findViewById(R.id.play_from_here_button).getVisibility()
                                    == View.VISIBLE,
                    3000);
            assertInsideDisplay(active.findViewById(R.id.play_from_here_button));
            assertTrue(
                    active.findViewById(R.id.play_from_here_button).getTop()
                            < active.findViewById(R.id.reader_scroll).getBottom());
            getInstrumentation()
                    .runOnMainSync(
                            () -> active.findViewById(R.id.play_from_here_button).performClick());
            Thread.sleep(1800);
            assertFalse(text(active, R.id.position).startsWith("00:00 /"));
            assertEquals("1.3", db.settingValue("playback_speed", ""));
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);
            getInstrumentation().waitForIdleSync();
            player = null;
            assertTrue(
                    "Book playback must advance and persist",
                    db.progress(book.id, chapters.get(0).id) > 0);
        } finally {
            if (player != null) {
                player.finish();
            }
            db.close();
        }
    }

    public void test03MusicScanPlaybackLyricsAndSettings() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        MusicLibraryDb db = new MusicLibraryDb(context);
        Activity player = null;
        String oldSpeed = db.settingValue("playback_speed", "1.0");
        String oldMode = db.settingValue("playback_mode", "stop_after_track");
        try {
            File dir = MusicScanner.musicDir(context);
            copyAsset("device-smoke.m4a", new File(dir, "device-smoke.m4a"));
            copyAsset("device-smoke.mp3", new File(dir, "device-smoke.mp3"));
            copyAsset("device-smoke.flac", new File(dir, "device-smoke.flac"));
            copyAsset("device-smoke.lrc", new File(dir, "device-smoke.lrc"));
            assertTrue(MusicScanner.scan(context, db) >= 3);
            List<MusicModels.Track> tracks = db.tracks();
            assertTrue(tracks.size() >= 3);
            Activity library =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, MusicLibraryActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertTrue(library.findViewById(R.id.music_list).getHeight() >= 180);
            assertTrue(library.findViewById(R.id.music_list).getWidth() >= 360);
            assertEquals("添加音乐/歌词", text(library, R.id.add_music_button));
            assertInsideDisplay(library.findViewById(R.id.add_music_button));
            assertInsideDisplay(library.findViewById(R.id.scan_button));
            library.finish();
            for (MusicModels.Track item : tracks) {
                assertFalse(item.toString().startsWith("★"));
            }
            MusicModels.Track track = findTrack(tracks, "device-smoke.m4a");
            MusicModels.Track flac = findTrack(tracks, "device-smoke.flac");
            assertTrue(track.durationMs > 0);
            assertTrue(flac.durationMs > 0);
            assertNotNull(track.lrcPath);
            assertNotNull(flac.lrcPath);
            if (track.favorite) {
                db.toggleFavorite(track.id);
            }
            db.putSetting("playback_speed", "1.25");
            db.putSetting("playback_mode", "repeat_all");
            db.saveProgress(track.id, 0);
            Intent intent =
                    new Intent(context, MusicPlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("track_id", track.id);
            player = getInstrumentation().startActivitySync(intent);
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.title).length() > 0
                                    && text(active, R.id.position).contains(" / "),
                    TIMEOUT_MS);
            assertEquals(
                    "Selected music track must own the ready player",
                    track.title,
                    text(active, R.id.title));
            TextView titleView = active.findViewById(R.id.title);
            String longTitle = "公开测试：这是一首用于验证超宽歌曲标题横向滚动显示的很长歌曲名称";
            getInstrumentation().runOnMainSync(() -> titleView.setText(longTitle));
            getInstrumentation().waitForIdleSync();
            assertTrue(
                    "Long music title must overflow its marquee viewport",
                    titleView.getPaint().measureText(longTitle)
                            > titleView.getWidth()
                                    - titleView.getCompoundPaddingLeft()
                                    - titleView.getCompoundPaddingRight());
            getInstrumentation().runOnMainSync(() -> titleView.setText(track.title));
            assertInsideDisplay(active.findViewById(R.id.title));
            assertTrue(((TextView) active.findViewById(R.id.title)).isSelected());
            assertEquals(
                    android.text.TextUtils.TruncateAt.MARQUEE,
                    ((TextView) active.findViewById(R.id.title)).getEllipsize());
            HorizontalPager musicPager = active.findViewById(R.id.player_pager);
            assertEquals(0, musicPager.currentPage());
            int[] musicControls = {
                R.id.previous_button,
                R.id.play_button,
                R.id.next_button,
                R.id.mode_button,
                R.id.volume_button,
                R.id.output_button
            };
            for (int id : musicControls) {
                View control = active.findViewById(id);
                assertInsideDisplay(control);
                assertTrue(control instanceof ImageButton);
                assertNotNull(((ImageButton) control).getDrawable());
                assertTrue(description(active, id).length() > 0);
            }
            assertMinimumTouchHeight(active.findViewById(R.id.play_button));
            assertNull(active.findViewById(R.id.favorite_button));
            assertNull(active.findViewById(R.id.speed_button));
            assertSameCenterY(active, R.id.previous_button, R.id.play_button, R.id.next_button);
            assertSameCenterY(active, R.id.mode_button, R.id.volume_button, R.id.output_button);
            assertTrue(
                    active.findViewById(R.id.mode_button).getTop()
                            > active.findViewById(R.id.play_button).getTop());
            swipe(active, 320, 255);
            waitFor(
                    () ->
                            musicPager.currentPage() == 1
                                    && Math.abs(musicPager.getScrollX() - musicPager.getWidth())
                                            <= 2,
                    3000);
            assertEquals("歌词页，第 2 页，共 2 页", description(active, R.id.page_indicator));
            assertEquals(
                    1,
                    ((PageIndicatorView) active.findViewById(R.id.page_indicator)).currentPage());
            assertInsideDisplay(active.findViewById(R.id.title));
            waitFor(() -> isInsideDisplay(active.findViewById(R.id.lyric_scroll)), 3000);
            assertInsideDisplay(active.findViewById(R.id.lyric_scroll));
            ScrollView lyricsScroll = active.findViewById(R.id.lyric_scroll);
            TextView lyrics = active.findViewById(R.id.full_lyrics);
            assertFullScreenNativeRoundScrollbar(active, lyricsScroll);
            assertTrue(lyrics.getWidth() > 0);
            assertTrue(
                    "Independent lyric page must expose more than a three-line window",
                    lyrics.getLayout() != null
                            && lyrics.getLayout().getLineCount() >= 9
                            && lyricsScroll.getHeight() / lyrics.getLineHeight() >= 6);
            assertTrue(
                    "Complete lyrics must retain lines far beyond previous/current/next",
                    text(active, R.id.full_lyrics).contains("第九句完成多行歌词验证"));
            waitFor(() -> highlightedLineCenter(lyrics, lyricsScroll) >= 0, 3000);
            assertTrue(
                    "Current lyric must be centered before manual browsing",
                    Math.abs(
                                    highlightedLineCenter(lyrics, lyricsScroll)
                                            - lyricsScroll.getHeight() / 2)
                            <= lyrics.getLineHeight());
            int lyricMax =
                    Math.max(0, lyricsScroll.getChildAt(0).getHeight() - lyricsScroll.getHeight());
            assertTrue("Full lyric page must be scrollable", lyricMax > 4);
            long lyricTouch = SystemClock.uptimeMillis();
            getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                lyricsScroll.dispatchTouchEvent(
                                        MotionEvent.obtain(
                                                lyricTouch,
                                                lyricTouch,
                                                MotionEvent.ACTION_DOWN,
                                                120,
                                                180,
                                                0));
                                lyricsScroll.scrollTo(0, Math.min(lyricMax, 90));
                                lyricsScroll.dispatchTouchEvent(
                                        MotionEvent.obtain(
                                                lyricTouch,
                                                lyricTouch + 120,
                                                MotionEvent.ACTION_UP,
                                                120,
                                                80,
                                                0));
                            });
            assertTrue(lyricsScroll.getScrollY() > 0);
            swipe(active, 145, 210);
            waitFor(() -> musicPager.currentPage() == 0 && musicPager.getScrollX() <= 2, 3000);
            assertEquals("列表循环", description(active, R.id.mode_button));
            assertTrue(description(active, R.id.volume_button).startsWith("音量 "));
            assertTrue(
                    "扬声器".equals(description(active, R.id.output_button))
                            || "蓝牙".equals(description(active, R.id.output_button)));
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            waitFor(
                    "full music lyrics and playback progress",
                    () ->
                            text(active, R.id.full_lyrics).contains("逐字歌词同步")
                                    && !text(active, R.id.position).startsWith("00:00 /"),
                    10000);
            CharSequence rendered = ((TextView) active.findViewById(R.id.full_lyrics)).getText();
            assertTrue(rendered instanceof Spanned);
            Spanned highlighted = (Spanned) rendered;
            assertTrue(
                    "Current lyric must retain an accent highlight while browsing",
                    highlighted.getSpans(0, highlighted.length(), ForegroundColorSpan.class).length
                                    > 0
                            && highlighted.getSpans(0, highlighted.length(), StyleSpan.class).length
                                    > 0);
            getInstrumentation().runOnMainSync(() -> musicPager.setCurrentPage(1, false));
            waitFor(
                    "lyric follow after playback resumes",
                    () ->
                            Math.abs(
                                            highlightedLineCenter(lyrics, lyricsScroll)
                                                    - lyricsScroll.getHeight() / 2)
                                    <= lyrics.getLineHeight(),
                    5000);
            assertInsideDisplay(active.findViewById(R.id.title));
            getInstrumentation().runOnMainSync(() -> musicPager.setCurrentPage(0, false));
            assertEquals("1.25", db.settingValue("playback_speed", ""));
            assertEquals("repeat_all", db.settingValue("playback_mode", ""));
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);
            getInstrumentation().waitForIdleSync();
            player = null;
            assertTrue("Music playback must advance and persist", db.progress(track.id) > 0);
        } finally {
            if (player != null) {
                player.finish();
            }
            db.putSetting("playback_speed", oldSpeed);
            db.putSetting("playback_mode", oldMode);
            removeMusicFixtures(context, db);
            db.close();
        }
    }

    public void test04Mp3SystemDecoderAndProgress() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        MusicLibraryDb db = new MusicLibraryDb(context);
        Activity player = null;
        try {
            ensureMusicFixtures(context, db);
            MusicModels.Track track = findTrack(db.tracks(), "device-smoke.mp3");
            db.saveProgress(track.id, 0);
            Intent intent =
                    new Intent(context, MusicPlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("track_id", track.id);
            player = getInstrumentation().startActivitySync(intent);
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.title).length() > 0
                                    && text(active, R.id.position).contains(" / "),
                    TIMEOUT_MS);
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            Thread.sleep(1800);
            assertFalse(text(active, R.id.position).startsWith("00:00 /"));
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);
            getInstrumentation().waitForIdleSync();
            player = null;
            assertTrue(db.progress(track.id) > 0);
        } finally {
            if (player != null) {
                player.finish();
            }
            removeMusicFixtures(context, db);
            db.close();
        }
    }

    public void test04bFlacMedia3DecoderAndProgress() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        MusicLibraryDb db = new MusicLibraryDb(context);
        Activity player = null;
        try {
            ensureMusicFixtures(context, db);
            MusicModels.Track track = findTrack(db.tracks(), "device-smoke.flac");
            db.saveProgress(track.id, 0);
            Intent intent =
                    new Intent(context, MusicPlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("track_id", track.id);
            player = getInstrumentation().startActivitySync(intent);
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.title).length() > 0
                                    && text(active, R.id.position).contains(" / "),
                    TIMEOUT_MS);
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            Thread.sleep(1800);
            assertFalse(text(active, R.id.position).startsWith("00:00 /"));
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);
            getInstrumentation().waitForIdleSync();
            player = null;
            assertTrue(db.progress(track.id) > 0);
        } finally {
            if (player != null) {
                player.finish();
            }
            removeMusicFixtures(context, db);
            db.close();
        }
    }

    public void test05RightSwipeExit() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        Activity chapters = null;
        try {
            LibraryModels.BookSummary book = goldenBook(db);
            Intent intent =
                    new Intent(context, ChaptersActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("book_id", book.id)
                            .putExtra("book_title", book.title);
            chapters = getInstrumentation().startActivitySync(intent);
            final Activity active = chapters;
            waitFor(() -> active.findViewById(R.id.chapter_list).getWidth() >= 360, 3000);
            long time = SystemClock.uptimeMillis();
            getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                active.dispatchTouchEvent(
                                        MotionEvent.obtain(
                                                time, time, MotionEvent.ACTION_DOWN, 40, 230, 0));
                                active.dispatchTouchEvent(
                                        MotionEvent.obtain(
                                                time,
                                                time + 200,
                                                MotionEvent.ACTION_UP,
                                                180,
                                                230,
                                                0));
                            });
            assertTrue(active.isFinishing());
            chapters = null;
        } finally {
            if (chapters != null) {
                chapters.finish();
            }
            db.close();
        }
    }

    public void test06FiveTimesBookPlayback() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        Activity player = null;
        try {
            LibraryModels.BookSummary book = goldenBook(db);
            LibraryModels.ChapterSummary chapter = db.chapters(book.id).get(0);
            db.putSetting("playback_speed", "5.0");
            db.saveProgress(book.id, chapter.id, 0);
            Intent intent =
                    new Intent(context, PlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("book_id", book.id)
                            .putExtra("track_id", chapter.id);
            player = getInstrumentation().startActivitySync(intent);
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.position).contains(" / "),
                    TIMEOUT_MS);
            assertEquals("倍速 5.0×", description(active, R.id.speed_button));
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            waitFor(() -> !text(active, R.id.position).startsWith("00:00 /"), 3000);
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            assertTrue(db.progress(book.id, chapter.id) > 0);
        } finally {
            db.putSetting("playback_speed", "1.25");
            if (player != null) {
                player.finish();
            }
            db.close();
        }
    }

    public void test07FixedOpaqueChoiceHeader() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        SharedPreferences target = context.getSharedPreferences("playback_target", 0);
        Map<String, ?> savedTarget = target.getAll();
        target.edit().clear().commit();
        MainActivity activity = getActivity();
        restore(target, savedTarget);
        String[] labels = new String[18];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "第" + (i + 1) + "章 公开章节标题";
        }
        Dialog[] holder = new Dialog[1];
        getInstrumentation()
                .runOnMainSync(
                        () ->
                                holder[0] =
                                        RoundChoiceDialog.show(
                                                activity, "章节", labels, 6, which -> { }));
        getInstrumentation().waitForIdleSync();
        TextView header = holder[0].findViewById(R.id.choice_dialog_title);
        ListView list = holder[0].findViewById(R.id.choice_dialog_list);
        assertNotNull(header);
        assertNotNull(list);
        assertEquals("章节", header.getText().toString());
        assertEquals(6, list.getCheckedItemPosition());
        assertTrue(
                "Current chapter must be visible when the directory opens",
                list.getFirstVisiblePosition() <= 6 && list.getLastVisiblePosition() >= 6);
        assertTrue(header.getBackground() != null);
        assertTrue(header.getBottom() <= list.getTop());
        int top = header.getTop();
        getInstrumentation().runOnMainSync(() -> list.setSelection(labels.length - 1));
        Thread.sleep(300);
        assertEquals(top, header.getTop());
        assertEquals("章节", header.getText().toString());
        getInstrumentation().runOnMainSync(holder[0]::dismiss);
    }

    public void test08MusicShuffleMode() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        MusicLibraryDb db = new MusicLibraryDb(context);
        Activity player = null;
        com.google.common.util.concurrent.ListenableFuture<MediaController> future = null;
        String oldMode = db.settingValue("playback_mode", "stop_after_track");
        try {
            ensureMusicFixtures(context, db);
            MusicModels.Track track = findTrack(db.tracks(), "device-smoke.m4a");
            db.putSetting("playback_mode", "shuffle");
            player =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, MusicPlayerActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("track_id", track.id));
            final Activity active = player;
            waitFor(() -> "列表随机".equals(description(active, R.id.mode_button)), TIMEOUT_MS);
            AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>>
                    pending = new AtomicReference<>();
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
            MediaController session = future.get(10, TimeUnit.SECONDS);
            AtomicReference<Boolean> shuffle = new AtomicReference<>();
            AtomicReference<Integer> repeat = new AtomicReference<>();
            getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                shuffle.set(session.getShuffleModeEnabled());
                                repeat.set(session.getRepeatMode());
                            });
            assertTrue(shuffle.get());
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, (int) repeat.get());
        } finally {
            if (future != null) {
                com.google.common.util.concurrent.ListenableFuture<MediaController> release =
                        future;
                getInstrumentation().runOnMainSync(() -> MediaController.releaseFuture(release));
            }
            db.putSetting("playback_mode", oldMode);
            if (player != null) {
                Activity closing = player;
                getInstrumentation().runOnMainSync(closing::finish);
                getInstrumentation().waitForIdleSync();
            }
            removeMusicFixtures(context, db);
            db.close();
        }
    }

    public void test09BookPlayerExitReturnsToChaptersWithoutLateTaskCrash() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        Activity chapters = null;
        Activity player = null;
        try {
            if (!db.containsBook("x3-golden-sample-ebfa1632c722")) {
                Activity library =
                        getInstrumentation()
                                .startActivitySync(
                                        new Intent(context, BookLibraryActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    ensureBook(library, db);
                } finally {
                    library.finish();
                }
            }
            LibraryModels.BookSummary book = goldenBook(db);
            LibraryModels.ChapterSummary chapter = db.chapters(book.id).get(0);
            chapters =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, ChaptersActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("book_id", book.id)
                                            .putExtra("book_title", book.title));
            final Activity chapterList = chapters;
            waitFor(() -> chapterList.findViewById(R.id.chapter_list).getWidth() >= 360, 3000);
            assertInsideDisplay(chapterList.findViewById(R.id.search_button));
            String firstLabel =
                    ((ListView) chapterList.findViewById(R.id.chapter_list))
                            .getItemAtPosition(0)
                            .toString();
            assertTrue(firstLabel.endsWith(ChapterLabels.numbered(1, chapter.title)));
            player =
                    getInstrumentation()
                            .startActivitySync(
                                    new Intent(context, PlayerActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("book_id", book.id)
                                            .putExtra("track_id", chapter.id));
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.position).contains(" / "),
                    TIMEOUT_MS);
            getInstrumentation().runOnMainSync(active::finish);
            getInstrumentation().waitForIdleSync();
            Thread.sleep(800);
            player = null;
            assertTrue(active.isDestroyed());
            assertFalse(chapterList.isFinishing());
            assertFalse(chapterList.isDestroyed());
            assertTrue(chapterList.findViewById(R.id.chapter_list).getWidth() >= 360);
        } finally {
            if (player != null) {
                player.finish();
            }
            if (chapters != null) {
                chapters.finish();
            }
            LibraryModels.BookStorage storage = db.storage("x3-golden-sample-ebfa1632c722");
            db.removeBook("x3-golden-sample-ebfa1632c722");
            BookMediaCache.clear(context, "x3-golden-sample-ebfa1632c722");
            if (storage != null) {
                OpbookImporter.delete(new File(storage.rootPath));
            }
            File packageFile =
                    new File(
                            context.getExternalFilesDir("test-packages"),
                            "golden-two-volume.opbook");
            packageFile.delete();
            assertFalse(db.containsBook("x3-golden-sample-ebfa1632c722"));
            assertFalse(packageFile.exists());
            db.close();
        }
    }

    public void test10SpeedSliderRangeAndRoundLayout() throws Exception {
        MainActivity activity = getActivity();
        Dialog[] holder = new Dialog[1];
        float[] selected = {-1f};
        getInstrumentation()
                .runOnMainSync(
                        () ->
                                holder[0] =
                                        RoundSpeedDialog.show(
                                                activity, 1.3f, value -> selected[0] = value));
        getInstrumentation().waitForIdleSync();
        SeekBar seek = holder[0].findViewById(R.id.speed_dialog_seek);
        TextView value = holder[0].findViewById(R.id.speed_dialog_value);
        assertNotNull(seek);
        assertEquals(45, seek.getMax());
        assertEquals(8, seek.getProgress());
        assertEquals("1.3×", value.getText().toString());
        assertInsideDisplay(seek);
        assertInsideDisplay(value);
        assertInsideDisplay(holder[0].findViewById(R.id.speed_dialog_decrease));
        assertInsideDisplay(holder[0].findViewById(R.id.speed_dialog_increase));
        assertInsideDisplay(holder[0].findViewById(R.id.speed_dialog_close));
        getInstrumentation().runOnMainSync(() -> seek.setProgress(45));
        getInstrumentation().waitForIdleSync();
        assertEquals("5.0×", value.getText().toString());
        assertEquals(5f, selected[0]);
        getInstrumentation().runOnMainSync(() -> seek.setProgress(0));
        getInstrumentation().waitForIdleSync();
        assertEquals("0.5×", value.getText().toString());
        assertEquals(0.5f, selected[0]);
        getInstrumentation().runOnMainSync(holder[0]::dismiss);
    }

    private static String text(Activity activity, int id) {
        CharSequence value = ((TextView) activity.findViewById(id)).getText();
        return value == null ? "" : value.toString();
    }

    private static String description(Activity activity, int id) {
        CharSequence value = activity.findViewById(id).getContentDescription();
        return value == null ? "" : value.toString();
    }

    private static int highlightedLineCenter(TextView lyrics, ScrollView scroll) {
        CharSequence value = lyrics.getText();
        if (!(value instanceof Spanned) || lyrics.getLayout() == null) {
            return -1;
        }
        Spanned styled = (Spanned) value;
        ForegroundColorSpan[] spans =
                styled.getSpans(0, styled.length(), ForegroundColorSpan.class);
        if (spans.length == 0) {
            return -1;
        }
        int line = lyrics.getLayout().getLineForOffset(styled.getSpanStart(spans[0]));
        return lyrics.getTop()
                + lyrics.getCompoundPaddingTop()
                + (lyrics.getLayout().getLineTop(line) + lyrics.getLayout().getLineBottom(line)) / 2
                - scroll.getScrollY();
    }

    private static void assertCompactOutput(Activity activity) {
        String label = text(activity, R.id.output_button);
        assertTrue("扬声器".equals(label) || "蓝牙".equals(label));
    }

    private static MusicModels.Track findTrack(List<MusicModels.Track> tracks, String suffix) {
        for (MusicModels.Track track : tracks) {
            if (track.path.endsWith(suffix)) {
                return track;
            }
        }
        fail("Missing music fixture: " + suffix);
        return null;
    }

    private static int findBookIndex(List<LibraryModels.BookSummary> books, String id) {
        for (int i = 0; i < books.size(); i++) {
            if (id.equals(books.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private static void removeMusicFixtures(Context context, MusicLibraryDb db) {
        File dir = MusicScanner.musicDir(context);
        for (MusicModels.Track track : db.tracks()) {
            String name = new File(track.path).getName();
            if ("device-smoke.m4a".equals(name)
                    || "device-smoke.mp3".equals(name)
                    || "device-smoke.flac".equals(name)) {
                assertTrue(db.removeTrack(track.id, true));
            }
        }
        new File(dir, "device-smoke.lrc").delete();
        String[] smokeFiles = {
            "device-smoke.m4a", "device-smoke.mp3", "device-smoke.flac", "device-smoke.lrc"
        };
        for (String name : smokeFiles) {
            assertFalse(new File(dir, name).exists());
        }
        for (MusicModels.Track track : db.tracks()) {
            assertFalse(new File(track.path).getName().startsWith("device-smoke."));
        }
    }

    private void ensureMusicFixtures(Context context, MusicLibraryDb db) throws Exception {
        File dir = MusicScanner.musicDir(context);
        copyAsset("device-smoke.m4a", new File(dir, "device-smoke.m4a"));
        copyAsset("device-smoke.mp3", new File(dir, "device-smoke.mp3"));
        copyAsset("device-smoke.flac", new File(dir, "device-smoke.flac"));
        copyAsset("device-smoke.lrc", new File(dir, "device-smoke.lrc"));
        assertTrue(MusicScanner.scan(context, db) >= 3);
    }

    private void ensureBook(Activity activity, LibraryDb db) throws Exception {
        for (LibraryModels.BookSummary book : db.books()) {
            if ("x3-golden-sample-ebfa1632c722".equals(book.id)) {
                return;
            }
        }
        File target =
                new File(activity.getExternalFilesDir("test-packages"), "golden-two-volume.opbook");
        copyAsset("golden-two-volume.opbook", target);
        assertTrue(
                BookImportCoordinator.get()
                        .start(
                                activity,
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
                TIMEOUT_MS);
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

    private static void assertInsideDisplay(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        String bounds =
                "x="
                        + at[0]
                        + ", y="
                        + at[1]
                        + ", w="
                        + view.getWidth()
                        + ", h="
                        + view.getHeight();
        assertTrue(bounds, view.getVisibility() == View.VISIBLE);
        assertTrue(bounds, at[0] >= 0 && at[1] >= 0);
        assertTrue(bounds, at[0] + view.getWidth() <= 466);
        assertTrue(bounds, at[1] + view.getHeight() <= 466);
    }

    private static boolean isInsideDisplay(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return view.getVisibility() == View.VISIBLE
                && at[0] >= 0
                && at[1] >= 0
                && at[0] + view.getWidth() <= 466
                && at[1] + view.getHeight() <= 466;
    }

    private static void assertMinimumTouchHeight(View view) {
        float density = view.getResources().getDisplayMetrics().density;
        assertTrue(
                "Playback touch target height must be at least 48dp",
                view.getHeight() + 1 >= Math.round(48 * density));
    }

    private static void assertStableIconState(ImageButton button) {
        assertEquals(1f, button.getAlpha());
        assertEquals(1f, button.getScaleX());
        assertEquals(1f, button.getScaleY());
        assertNull(button.getStateListAnimator());
        int enabled =
                button.getImageTintList()
                        .getColorForState(new int[] {android.R.attr.state_enabled}, 0);
        int disabled =
                button.getImageTintList()
                        .getColorForState(new int[] {-android.R.attr.state_enabled}, 0);
        assertEquals(
                "Icon tint must remain visually stable while audio prepares", enabled, disabled);
    }

    private static void assertEqualWidth(Activity activity, int... ids) {
        int width = activity.findViewById(ids[0]).getWidth();
        assertTrue(width > 0);
        for (int id : ids) {
            assertTrue(Math.abs(width - activity.findViewById(id).getWidth()) <= 1);
        }
    }

    private static void assertSameRow(Activity activity, int... ids) {
        int top = activity.findViewById(ids[0]).getTop();
        for (int id : ids) {
            assertTrue(Math.abs(top - activity.findViewById(id).getTop()) <= 4);
        }
    }

    private static void assertSameCenterY(Activity activity, int... ids) {
        View first = activity.findViewById(ids[0]);
        int center = first.getTop() + first.getHeight() / 2;
        for (int id : ids) {
            View view = activity.findViewById(id);
            assertTrue(Math.abs(center - (view.getTop() + view.getHeight() / 2)) <= 4);
        }
    }

    private static void assertFullScreenNativeRoundScrollbar(Activity activity, ScrollView scroll) {
        int[] at = new int[2];
        scroll.getLocationOnScreen(at);
        assertTrue(
                "Native round scrollbar needs the physical screen origin",
                Math.abs(at[0]) <= 1 && Math.abs(at[1]) <= 1);
        assertTrue(
                "Native round scrollbar needs the full physical viewport",
                Math.abs(scroll.getWidth() - 466) <= 1 && Math.abs(scroll.getHeight() - 466) <= 1);
        assertTrue(
                "Device must expose round-screen scrollbar rendering",
                activity.getResources().getConfiguration().isScreenRound());
        assertTrue(scroll.isVerticalScrollBarEnabled());
        assertEquals(View.SCROLLBARS_INSIDE_OVERLAY, scroll.getScrollBarStyle());
    }

    private static void restore(SharedPreferences target, Map<String, ?> values) {
        SharedPreferences.Editor editor = target.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            } else {
                if (value instanceof Boolean) {
                    editor.putBoolean(entry.getKey(), (Boolean) value);
                } else {
                    if (value instanceof Integer) {
                        editor.putInt(entry.getKey(), (Integer) value);
                    } else {
                        if (value instanceof Long) {
                            editor.putLong(entry.getKey(), (Long) value);
                        } else {
                            if (value instanceof Float) {
                                editor.putFloat(entry.getKey(), (Float) value);
                            }
                        }
                    }
                }
            }
        }
        editor.commit();
    }

    private void swipe(Activity activity, float fromX, float toX) {
        long now = SystemClock.uptimeMillis();
        float middle = (fromX + toX) / 2f;
        getInstrumentation()
                .runOnMainSync(
                        () -> {
                            activity.dispatchTouchEvent(
                                    MotionEvent.obtain(
                                            now, now, MotionEvent.ACTION_DOWN, fromX, 230, 0));
                            activity.dispatchTouchEvent(
                                    MotionEvent.obtain(
                                            now,
                                            now + 80,
                                            MotionEvent.ACTION_MOVE,
                                            middle,
                                            230,
                                            0));
                            activity.dispatchTouchEvent(
                                    MotionEvent.obtain(
                                            now, now + 160, MotionEvent.ACTION_MOVE, toX, 230, 0));
                            activity.dispatchTouchEvent(
                                    MotionEvent.obtain(
                                            now, now + 200, MotionEvent.ACTION_UP, toX, 230, 0));
                        });
    }

    private void waitFor(Check check, long timeout) throws Exception {
        waitFor("device state", check, timeout);
    }

    private void waitFor(String label, Check check, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            getInstrumentation().waitForIdleSync();
            if (check.ok()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + label);
    }

    private interface Check {

        boolean ok() throws Exception;
    }
}
