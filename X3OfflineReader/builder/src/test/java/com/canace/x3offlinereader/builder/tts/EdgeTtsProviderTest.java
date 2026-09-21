package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class EdgeTtsProviderTest {
    @Test
    void exposesConfiguredBoundedParallelism() {
        assertEquals(1, new EdgeTtsProvider(java.nio.file.Path.of(".")).parallelism());
        assertEquals(12, new EdgeTtsProvider(java.nio.file.Path.of("."), 12).parallelism());
        assertThrows(
                IllegalArgumentException.class,
                () -> new EdgeTtsProvider(java.nio.file.Path.of("."), 13));
    }

    @Test
    void reportsRootFailureInsteadOfWindowsEventLoopCleanupNoise() {
        String error =
                "edge_tts.exceptions.NoAudioReceived: No audio was received.\n"
                    + "Exception ignored in: <function _ProactorBasePipeTransport.__del__ at 0x1>\n"
                    + "Traceback\n"
                    + "RuntimeError: Event loop is closed";
        String clean = EdgeTtsProvider.sanitize(error);
        assertTrue(clean.contains("NoAudioReceived"));
        assertFalse(clean.contains("Event loop is closed"));
    }

    @Test
    void prefersStructuredWorkerError() {
        String clean =
                EdgeTtsProvider.sanitize(
                        "old traceback\n"
                                + "{\"errorType\":\"WSServerHandshakeError\",\"error\":\"403\"}\n");
        assertTrue(clean.startsWith("{\"errorType\""));
        assertTrue(clean.contains("403"));
    }

    @Test
    void retriesTransientFailuresWithBoundedBackoffButNotInvalidParameters() {
        assertTrue(EdgeTtsProvider.shouldRetry(new IllegalStateException("NoAudioReceived")));
        assertTrue(EdgeTtsProvider.shouldRetry(new IllegalStateException("ConnectionResetError")));
        assertFalse(
                EdgeTtsProvider.shouldRetry(
                        new IllegalStateException(
                                "{\"errorType\":\"ValueError\",\"error\":\"Invalid voice\"}")));
        assertFalse(EdgeTtsProvider.shouldRetry(new IllegalStateException("File not found")));
        assertEquals(2_000L, EdgeTtsProvider.retryDelayMillis(1));
        assertEquals(40_000L, EdgeTtsProvider.retryDelayMillis(5));
        assertEquals(60_000L, EdgeTtsProvider.retryDelayMillis(6));
    }

    @Test
    void exhaustedTransientFailureExplainsAutomaticPause() {
        String message =
                EdgeTtsProvider.temporaryPauseMessage(
                        25, new IllegalStateException("NoAudioReceived"));
        assertTrue(message.contains("持续恢复约 20 分钟"));
        assertTrue(message.contains("共尝试 25 次"));
        assertTrue(message.contains("已自动保存并暂停"));
        assertTrue(message.contains("NoAudioReceived"));
    }

    @Test
    void spokenCopyAddsOnlyNaturalTerminalPunctuation() {
        assertEquals("今天天气好。", EdgeTtsProvider.spokenText("今天天气好"));
        assertEquals("你好吗？", EdgeTtsProvider.spokenText("你好吗？"));
        assertEquals("标题。", EdgeTtsProvider.spokenText("  标题  "));
        assertEquals("“今天天气好。”", EdgeTtsProvider.spokenText("“今天天气好。”"));
        assertEquals("（你好吗？）", EdgeTtsProvider.spokenText("（你好吗？）"));
        assertThrows(IllegalArgumentException.class, () -> EdgeTtsProvider.spokenText("”……"));
    }

    @Test
    void transientNoAudioRecoversWithoutPausingTheTask() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        int attempts =
                EdgeTtsProvider.runWithRecovery(
                        () -> {
                            if (calls.incrementAndGet() <= 6) {
                                throw new IllegalStateException("NoAudioReceived");
                            }
                        },
                        millis -> sleeps.incrementAndGet(),
                        25);
        assertEquals(7, attempts);
        assertEquals(7, calls.get());
        assertEquals(6, sleeps.get());
    }
}
