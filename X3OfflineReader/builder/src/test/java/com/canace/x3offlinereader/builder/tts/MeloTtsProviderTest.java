package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MeloTtsProviderTest {
    @Test
    void adaptivePoolUsesTwoWorkersOnlyWithEnoughResources() {
        long gib = MeloTtsProviderPool.GIB;
        assertEquals(2, MeloTtsProviderPool.recommendedParallelism(32 * gib, 16 * gib, 16, 2));
        assertEquals(2, MeloTtsProviderPool.recommendedParallelism(32 * gib, 16 * gib, 16, 3));
        assertEquals(1, MeloTtsProviderPool.recommendedParallelism(16 * gib, 12 * gib, 16, 2));
        assertEquals(1, MeloTtsProviderPool.recommendedParallelism(32 * gib, 8 * gib, 16, 2));
        assertEquals(1, MeloTtsProviderPool.recommendedParallelism(32 * gib, 16 * gib, 8, 2));
    }

    @Test
    void splitsLongUnicodeTextWithoutChangingIt() {
        String text = "这是第一段，包含𠀀字符与一些内容，便于验证代码点边界。".repeat(20);
        List<String> pieces = MeloTtsProvider.splitText(text, 80);
        assertTrue(pieces.size() > 1);
        assertTrue(
                pieces.stream().allMatch(piece -> piece.codePointCount(0, piece.length()) <= 80));
        assertEquals(text, String.join("", pieces));
    }
}
