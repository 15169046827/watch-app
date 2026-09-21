package com.canace.x3offlinereader.playback;

import java.util.Locale;

/** Pure selection rule shared by Android crown input and JVM regression tests. */
public final class CrownInputContract {
    private CrownInputContract() { }

    public static float selectDelta(
            boolean actionScroll,
            boolean rotarySource,
            boolean mouseSource,
            String deviceName,
            float axisScroll,
            float axisVScroll) {
        if (!actionScroll) {
            return 0f;
        }
        if (rotarySource) {
            return axisScroll;
        }
        String normalized = deviceName == null ? "" : deviceName.toLowerCase(Locale.ROOT);
        return mouseSource && normalized.contains("oplus_crown") ? axisVScroll : 0f;
    }
}
