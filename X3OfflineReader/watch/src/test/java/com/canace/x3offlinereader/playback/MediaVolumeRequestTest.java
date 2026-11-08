package com.canace.x3offlinereader.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class MediaVolumeRequestTest {
    @Test
    void cappedIncreaseRequestsSystemUiOnce() {
        AtomicInteger prompts = new AtomicInteger();
        MediaVolumeRequest.apply(8, () -> 5, value -> assertEquals(8, value),
                value -> {
                    assertEquals(8, value);
                    prompts.incrementAndGet();
                });
        assertEquals(1, prompts.get());
    }

    @Test
    void acceptedIncreaseAndRejectedDecreaseDoNotRequestSystemUi() {
        AtomicInteger actual = new AtomicInteger(5);
        AtomicInteger prompts = new AtomicInteger();
        MediaVolumeRequest.apply(8, actual::get, actual::set, value -> prompts.incrementAndGet());
        MediaVolumeRequest.apply(4, actual::get, value -> { }, value -> prompts.incrementAndGet());
        MediaVolumeRequest.apply(8, actual::get, value -> { }, value -> prompts.incrementAndGet());
        assertEquals(0, prompts.get());
        assertEquals(8, actual.get());
    }
}
