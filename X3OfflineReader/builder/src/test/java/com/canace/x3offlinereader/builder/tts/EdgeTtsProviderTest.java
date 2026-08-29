package com.canace.x3offlinereader.builder.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class EdgeTtsProviderTest {
    @Test void reportsRootFailureInsteadOfWindowsEventLoopCleanupNoise() {
        String error="edge_tts.exceptions.NoAudioReceived: No audio was received.\nException ignored in: <function _ProactorBasePipeTransport.__del__ at 0x1>\nTraceback\nRuntimeError: Event loop is closed";
        String clean=EdgeTtsProvider.sanitize(error);
        assertTrue(clean.contains("NoAudioReceived"));
        assertFalse(clean.contains("Event loop is closed"));
    }

    @Test void prefersStructuredWorkerError() {
        String clean=EdgeTtsProvider.sanitize("old traceback\n{\"errorType\":\"WSServerHandshakeError\",\"error\":\"403\"}\n");
        assertTrue(clean.startsWith("{\"errorType\""));
        assertTrue(clean.contains("403"));
    }

    @Test void retriesTransientFailuresWithBoundedBackoffButNotInvalidParameters() {
        assertTrue(EdgeTtsProvider.shouldRetry(new IllegalStateException("NoAudioReceived")));
        assertTrue(EdgeTtsProvider.shouldRetry(new IllegalStateException("ConnectionResetError")));
        assertFalse(EdgeTtsProvider.shouldRetry(new IllegalStateException("{\"errorType\":\"ValueError\",\"error\":\"Invalid voice\"}")));
        assertFalse(EdgeTtsProvider.shouldRetry(new IllegalStateException("File not found")));
        assertEquals(2_000L,EdgeTtsProvider.retryDelayMillis(1));
        assertEquals(40_000L,EdgeTtsProvider.retryDelayMillis(5));
    }

    @Test void exhaustedTransientFailureExplainsAutomaticPause() {
        String message=EdgeTtsProvider.temporaryPauseMessage(6,new IllegalStateException("NoAudioReceived"));
        assertTrue(message.contains("共尝试 6 次"));
        assertTrue(message.contains("已自动保存并暂停"));
        assertTrue(message.contains("NoAudioReceived"));
    }
}
