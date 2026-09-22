package com.canace.x3offlinereader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WatchDataPolicyTest {
    @Test
    void identifiersRejectEmptyControlsAndOversizeValues() {
        assertTrue(WatchDataPolicy.validId("book-1"));
        assertFalse(WatchDataPolicy.validId(""));
        assertFalse(WatchDataPolicy.validId("book\n2"));
        assertFalse(WatchDataPolicy.validId("x".repeat(WatchDataPolicy.MAX_ID_LENGTH + 1)));
    }

    @Test
    void pathsRejectEmptyControlsAndOversizeValues() {
        assertTrue(WatchDataPolicy.validPath("Music/album/song.mp3"));
        assertFalse(WatchDataPolicy.validPath(null));
        assertFalse(WatchDataPolicy.validPath("Music/song\0.mp3"));
        assertFalse(WatchDataPolicy.validPath("x".repeat(WatchDataPolicy.MAX_PATH_LENGTH + 1)));
    }

    @Test
    void novelSpeedAcceptsOnlySupportedFiniteRange() {
        assertTrue(WatchDataPolicy.validNovelSetting("playback_speed", "0.5"));
        assertTrue(WatchDataPolicy.validNovelSetting("playback_speed", "5.0"));
        assertFalse(WatchDataPolicy.validNovelSetting("playback_speed", "NaN"));
        assertFalse(WatchDataPolicy.validNovelSetting("other", "1.0"));
        assertFalse(WatchDataPolicy.validNovelSetting("playback_speed", "5.1"));
    }

    @Test
    void musicModeUsesAllowList() {
        assertTrue(WatchDataPolicy.validMusicSetting("playback_mode", "shuffle"));
        assertTrue(WatchDataPolicy.validMusicSetting("playback_speed", "1.25"));
        assertFalse(WatchDataPolicy.validMusicSetting("playback_mode", "random"));
        assertFalse(WatchDataPolicy.validMusicSetting("other", "shuffle"));
    }
}
