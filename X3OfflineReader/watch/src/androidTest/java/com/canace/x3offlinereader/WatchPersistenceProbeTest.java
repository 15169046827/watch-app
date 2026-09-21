package com.canace.x3offlinereader;

import android.content.Context;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.music.MusicLibraryDb;

/**
 * Proves book and music settings survive a fresh database handle without leaving test data behind.
 */
public final class WatchPersistenceProbeTest extends InstrumentationTestCase {
    public void testPersistedSettingsAcrossReopen() {
        Context context = getInstrumentation().getTargetContext();
        LibraryDb books = new LibraryDb(context);
        MusicLibraryDb music = new MusicLibraryDb(context);
        String oldBookSpeed = books.settingValue("playback_speed", "1.0");
        String oldMusicSpeed = music.settingValue("playback_speed", "1.0");
        String oldMusicMode = music.settingValue("playback_mode", "stop_after_track");
        try {
            books.putSetting("playback_speed", "1.25");
            music.putSetting("playback_speed", "1.25");
            music.putSetting("playback_mode", "repeat_all");
            LibraryDb reopenedBooks = new LibraryDb(context);
            MusicLibraryDb reopenedMusic = new MusicLibraryDb(context);
            try {
                assertEquals("1.25", reopenedBooks.settingValue("playback_speed", ""));
                assertEquals("1.25", reopenedMusic.settingValue("playback_speed", ""));
                assertEquals("repeat_all", reopenedMusic.settingValue("playback_mode", ""));
            } finally {
                reopenedBooks.close();
                reopenedMusic.close();
            }
        } finally {
            books.putSetting("playback_speed", oldBookSpeed);
            music.putSetting("playback_speed", oldMusicSpeed);
            music.putSetting("playback_mode", oldMusicMode);
            books.close();
            music.close();
        }
    }
}
