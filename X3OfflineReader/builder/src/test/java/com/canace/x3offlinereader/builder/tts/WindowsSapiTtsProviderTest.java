package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WindowsSapiTtsProviderTest {
    @TempDir Path temporary;

    @Test
    void discoversChineseVoiceAndSynthesizesPlayableWave() throws Exception {
        try (WindowsSapiTtsProvider provider = new WindowsSapiTtsProvider()) {
            Assumptions.assumeTrue(provider.isAvailable());
            var voices = provider.voices();
            Assumptions.assumeFalse(voices.isEmpty());
            SynthesisResult result =
                    provider.synthesize(
                            new SynthesisRequest("preview", "这是系统语音试听验证。", voices.get(0), 1.0),
                            temporary);
            assertTrue(Files.size(result.getWavPath()) > 46);
            assertTrue(result.getDurationMs() > 0);
        }
    }
}
