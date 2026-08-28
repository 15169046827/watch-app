package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class TtsProviderCatalogTest {
    @Test void everyVisibleProviderStatesItsNetworkMode() {
        assertTrue(TtsProviderCatalog.entries().stream().allMatch(entry -> entry.label().startsWith("[离线") || entry.label().startsWith("[在线]") || entry.label().startsWith("[测试]")));
        assertTrue(TtsProviderCatalog.entry("edge").online());
        assertFalse(TtsProviderCatalog.entry("sherpa-kokoro").online());
    }

    @Test void exposesAllChineseSpeakerChoices() {
        assertEquals(100, TtsProviderCatalog.previewProvider("sherpa-kokoro", java.nio.file.Path.of(".").toAbsolutePath(), null).voices().size());
        assertEquals(174, TtsProviderCatalog.previewProvider("sherpa-aishell3", java.nio.file.Path.of(".").toAbsolutePath(), null).voices().size());
    }

    @Test void testToneIsNotShownAsAUserVoice() {
        assertTrue(TtsProviderCatalog.supports("tone"));
        assertFalse(TtsProviderCatalog.guiEntries().stream().anyMatch(entry -> entry.id().equals("tone")));
        assertEquals(5, TtsProviderCatalog.guiEntries().size());
    }

    @Test void previewKeysAreStableAndSentenceIsNaturalLength() {
        String first=VoicePreviewLibrary.key("sherpa-kokoro","中文女声 zf_001 · sid=3");
        assertEquals(first,VoicePreviewLibrary.key("sherpa-kokoro","中文女声 zf_001 · sid=3"));
        assertEquals(24,first.length());
        assertTrue(VoicePreviewLibrary.sentence("sherpa-kokoro").contains("自然语音"));
        assertEquals(VoicePreviewLibrary.sentence("sherpa-kokoro"),VoicePreviewLibrary.sentence("sherpa-aishell3"));
        assertEquals("你好，欢迎试听这段自然语音。",VoicePreviewLibrary.adjustedSentence("sherpa-kokoro",10001));
        assertTrue(VoicePreviewLibrary.adjustedSentence("sherpa-aishell3",2999).contains("每一段文字"));
        assertTrue(VoicePreviewLibrary.sentence("melo").contains("自然语音"));
        assertEquals(1.0,VoicePreviewLibrary.RATE);
    }
}
