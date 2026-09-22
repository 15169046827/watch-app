package com.canace.x3offlinereader.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PlaybackSpeedRangeTest {
    @Test
    void supportsEveryTenthFromHalfToFiveTimes() {
        assertEquals(45, PlaybackSpeedRange.MAX_PROGRESS);
        for (int progress = 0; progress <= 45; progress++) {
            assertEquals(progress, PlaybackSpeedRange.progress(PlaybackSpeedRange.speed(progress)));
        }
    }

    @Test
    void normalizesLegacyAndOutOfRangeValues() {
        assertEquals(1.3f, PlaybackSpeedRange.normalize(1.25f));
        assertEquals(0.5f, PlaybackSpeedRange.normalize(0.1f));
        assertEquals(5f, PlaybackSpeedRange.normalize(8f));
        assertEquals(1f, PlaybackSpeedRange.normalize(Float.NaN));
    }

    @Test
    void formatsOneDecimalMultiplier() {
        assertEquals("0.5×", PlaybackSpeedRange.label(0.5f));
        assertEquals("5.0×", PlaybackSpeedRange.label(5f));
    }
}
