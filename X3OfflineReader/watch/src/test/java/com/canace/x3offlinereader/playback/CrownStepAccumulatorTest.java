package com.canace.x3offlinereader.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CrownStepAccumulatorTest {
    @Test
    void mapsClockwiseNegativeToRaiseAndCounterclockwisePositiveToLower() {
        CrownStepAccumulator value = new CrownStepAccumulator();
        assertEquals(0, value.add(-0.4f, 10));
        assertEquals(1, value.add(-0.6f, 20));
        value.reset();
        assertEquals(-1, value.add(1f, 30));
    }

    @Test
    void limitsToOneStepPerHundredMilliseconds() {
        CrownStepAccumulator value = new CrownStepAccumulator();
        assertEquals(1, value.add(-3f, 10));
        assertEquals(0, value.add(-1f, 50));
        assertEquals(1, value.add(-0.1f, 110));
    }

    @Test
    void ignoresSmallOppositeTailButAllowsDeliberateReverse() {
        CrownStepAccumulator value = new CrownStepAccumulator();
        assertEquals(1, value.add(-1f, 10));
        assertEquals(0, value.add(0.25f, 120));
        assertEquals(0, value.add(0.7f, 130));
        assertEquals(-1, value.add(0.1f, 230));
    }

    @Test
    void sessionGapDropsOldFractionAndResetClearsDirection() {
        CrownStepAccumulator value = new CrownStepAccumulator();
        assertEquals(0, value.add(-0.6f, 10));
        assertEquals(0, value.add(-0.5f, 160));
        value.reset();
        assertEquals(-1, value.add(1f, 170));
    }
}
