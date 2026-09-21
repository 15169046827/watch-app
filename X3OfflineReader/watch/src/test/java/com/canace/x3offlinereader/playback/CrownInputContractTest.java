package com.canace.x3offlinereader.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CrownInputContractTest {
    @Test
    void standardRotaryUsesScrollAxis() {
        assertEquals(-2f, CrownInputContract.selectDelta(true, true, false, null, -2f, 9f));
    }

    @Test
    void oppoMouseCrownUsesVerticalAxis() {
        assertEquals(
                1.5f, CrownInputContract.selectDelta(true, false, true, "OPLUS_CROWN", 0f, 1.5f));
    }

    @Test
    void ignoresHoverAndUnrelatedMouse() {
        assertEquals(
                0f, CrownInputContract.selectDelta(false, false, true, "oplus_crown", 0f, -1f));
        assertEquals(0f, CrownInputContract.selectDelta(true, false, true, "touchpad", 0f, -1f));
    }
}
