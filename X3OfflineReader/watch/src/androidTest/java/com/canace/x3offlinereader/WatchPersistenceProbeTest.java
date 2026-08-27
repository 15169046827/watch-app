package com.canace.x3offlinereader;

import android.content.Context;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import java.util.List;

/** Run in a fresh instrumentation process after the smoke suite to prove SQLite restart persistence. */
public final class WatchPersistenceProbeTest extends InstrumentationTestCase {
    public void testPersistedCatalogSettingsFavoritesAndProgress(){
        Context context=getInstrumentation().getTargetContext();LibraryDb books=new LibraryDb(context);MusicLibraryDb music=new MusicLibraryDb(context);
        try {
            List<LibraryModels.BookSummary> catalog=books.books();assertFalse(catalog.isEmpty());LibraryModels.BookSummary book=null;for(LibraryModels.BookSummary item:catalog)if("x3-golden-sample-ebfa1632c722".equals(item.id))book=item;assertNotNull(book);assertTrue(books.isFavorite(book.id));assertEquals("1.25",books.settingValue("playback_speed",""));
            boolean bookProgress=false;for(LibraryModels.ChapterSummary chapter:books.chapters(book.id))bookProgress|=books.progress(book.id,chapter.id)>0;assertTrue(bookProgress);
            List<MusicModels.Track> tracks=music.tracks();assertTrue(tracks.size()>=2);boolean musicProgress=false;boolean favorite=false;for(MusicModels.Track track:tracks){musicProgress|=music.progress(track.id)>0;favorite|=track.favorite;}assertTrue(musicProgress);assertTrue(favorite);assertEquals("1.25",music.settingValue("playback_speed",""));assertEquals("repeat_all",music.settingValue("playback_mode",""));
        } finally {books.close();music.close();}
    }
}
