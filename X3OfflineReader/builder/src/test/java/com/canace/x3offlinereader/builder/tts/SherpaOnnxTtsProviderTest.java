package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

final class SherpaOnnxTtsProviderTest {
    @Test void parsesStableSpeakerIdFromFriendlyLabel() { assertEquals(102, SherpaOnnxTtsProvider.parseSid("中文男声 45 · sid=102")); }
    @Test void rejectsAmbiguousVoiceLabel() { assertThrows(IllegalArgumentException.class, () -> SherpaOnnxTtsProvider.parseSid("女声 1")); }
}
