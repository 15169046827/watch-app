package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SherpaOnnxTtsProviderTest {
    @TempDir Path temporary;

    @Test
    void parsesStableSpeakerIdFromFriendlyLabel() {
        assertEquals(102, SherpaOnnxTtsProvider.parseSid("中文男声 45 · sid=102"));
    }

    @Test
    void rejectsAmbiguousVoiceLabel() {
        assertThrows(IllegalArgumentException.class, () -> SherpaOnnxTtsProvider.parseSid("女声 1"));
    }

    @Test
    void preservesSupportedChineseAsCharactersInsteadOfCharArrayIdentities() throws Exception {
        Files.writeString(
                temporary.resolve("lexicon-zh.txt"),
                "欢 h\n迎 y\n试 s\n听 t\n",
                StandardCharsets.UTF_8);
        SherpaOnnxTtsProvider provider =
                new SherpaOnnxTtsProvider(temporary, SherpaOnnxTtsProvider.Model.KOKORO);
        Method normalize =
                SherpaOnnxTtsProvider.class.getDeclaredMethod(
                        "normalizeText", String.class, Path.class);
        normalize.setAccessible(true);
        String result = (String) normalize.invoke(provider, "欢迎试听", temporary);
        assertEquals("欢迎试听", result);
        assertNotEquals("[C@", result.substring(0, Math.min(3, result.length())));
    }
}
