package com.canace.x3offlinereader.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class MusicFileInfoTest {
    @Test void preservesUnicodeAndReplacesOnlyUnsafeCharacters(){
        assertEquals("陈奕迅-十年_.flac",MusicFileInfo.safeFileName("陈奕迅-十年?.flac"));
        assertEquals("Ike Eveland-Chronos.mp3",MusicFileInfo.safeFileName("Ike Eveland-Chronos.mp3"));
    }

    @Test void rejectsReplacementMetadataAndFallsBackToArtistTitleFileName(){
        MusicFileInfo.Labels labels=MusicFileInfo.labels("陈奕迅-十年.flac","����","����Ѹ");
        assertEquals("十年",labels.title);
        assertEquals("陈奕迅",labels.artist);
    }

    @Test void validMetadataRemainsAuthoritative(){
        MusicFileInfo.Labels labels=MusicFileInfo.labels("文件名.mp3","Track title","Artist");
        assertEquals("Track title",labels.title);
        assertEquals("Artist",labels.artist);
    }

    @Test void blankAndControlMetadataAreRejected(){
        assertNull(MusicFileInfo.cleanMetadata("  "));
        assertNull(MusicFileInfo.cleanMetadata("bad\u0000title"));
    }
}
