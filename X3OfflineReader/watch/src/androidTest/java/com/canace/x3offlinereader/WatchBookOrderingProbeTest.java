package com.canace.x3offlinereader;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import java.io.File;
import java.util.List;

/** Verifies recently played books lead the library without retaining probe rows. */
public final class WatchBookOrderingProbeTest extends InstrumentationTestCase {
    private static final String OLDER = "__ordering_older__";
    private static final String NEWER = "__ordering_newer__";
    private static final String UNPLAYED = "__ordering_unplayed__";

    public void testBooksSortByLatestPlaybackThenUnplayed() {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb db = new LibraryDb(context);
        try {
            removeProbeBooks(db);
            db.insertBook(book(context, OLDER, "较早播放"));
            db.insertBook(book(context, NEWER, "最近播放"));
            db.insertBook(book(context, UNPLAYED, "从未播放"));
            db.saveProgress(OLDER, OLDER + ":chapter", OLDER + ":track", 0, 0);
            SystemClock.sleep(5);
            db.saveProgress(NEWER, NEWER + ":chapter", NEWER + ":track", 0, 0);

            List<LibraryModels.BookSummary> books = db.books();
            assertTrue(indexOf(books, NEWER) < indexOf(books, OLDER));
            assertTrue(indexOf(books, OLDER) < indexOf(books, UNPLAYED));
        } finally {
            removeProbeBooks(db);
            db.close();
        }
    }

    private static LibraryModels.Book book(Context context, String id, String title) {
        LibraryModels.Book book = new LibraryModels.Book();
        book.id = id;
        book.title = title;
        book.author = "";
        book.rootPath = new File(context.getCacheDir(), id).getAbsolutePath();
        LibraryModels.Volume volume = new LibraryModels.Volume();
        volume.id = "volume";
        volume.title = "正文";
        volume.order = 0;
        LibraryModels.Chapter chapter = new LibraryModels.Chapter();
        chapter.id = "chapter";
        chapter.title = "第一章";
        chapter.order = 0;
        chapter.contentPath = "content.json";
        LibraryModels.Track track = new LibraryModels.Track();
        track.id = "track";
        track.order = 0;
        track.audioPath = "audio.m4a";
        track.timelinePath = "timeline.json";
        track.durationMs = 1000;
        chapter.tracks.add(track);
        volume.chapters.add(chapter);
        book.volumes.add(volume);
        return book;
    }

    private static int indexOf(List<LibraryModels.BookSummary> books, String id) {
        for (int i = 0; i < books.size(); i++) {
            if (id.equals(books.get(i).id)) {
                return i;
            }
        }
        fail("Missing probe book: " + id);
        return Integer.MAX_VALUE;
    }

    private static void removeProbeBooks(LibraryDb db) {
        db.removeBook(OLDER);
        db.removeBook(NEWER);
        db.removeBook(UNPLAYED);
    }
}
