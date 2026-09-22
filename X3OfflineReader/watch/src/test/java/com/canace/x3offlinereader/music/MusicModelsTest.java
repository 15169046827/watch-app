package com.canace.x3offlinereader.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MusicModelsTest {
    @Test
    void favoriteTrackDoesNotShowAStarInTheLibrary() {
        MusicModels.Track track =
                new MusicModels.Track(1, "公开歌曲", "歌手", "song.m4a", null, 1000, true);
        assertEquals("公开歌曲\n歌手", track.toString());
    }
}
