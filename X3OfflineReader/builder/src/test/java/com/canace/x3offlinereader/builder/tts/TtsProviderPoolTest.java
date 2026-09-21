package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TtsProviderPoolTest {
    @Test
    void capsWindowsWorkersByRequestCpuAndHardLimit() {
        assertEquals(12, WindowsSapiTtsProviderPool.recommendedParallelism(16, 12));
        assertEquals(4, WindowsSapiTtsProviderPool.recommendedParallelism(4, 12));
        assertEquals(2, WindowsSapiTtsProviderPool.recommendedParallelism(16, 2));
        assertEquals(1, WindowsSapiTtsProviderPool.recommendedParallelism(0, 0));
    }

    @Test
    void capsSherpaWorkersToProtectCpuAndMemory() {
        assertEquals(4, SherpaOnnxTtsProviderPool.recommendedParallelism(16, 4));
        assertEquals(2, SherpaOnnxTtsProviderPool.recommendedParallelism(8, 4));
        assertEquals(1, SherpaOnnxTtsProviderPool.recommendedParallelism(4, 4));
        assertEquals(1, SherpaOnnxTtsProviderPool.recommendedParallelism(16, 0));
    }
}
