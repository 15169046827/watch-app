package com.canace.x3offlinereader.playback;

import java.util.Locale;

/** 0.5x-5.0x playback speeds represented as exact tenths for the Watch slider. */
public final class PlaybackSpeedRange {
    public static final int MIN_TENTHS = 5;
    public static final int MAX_TENTHS = 50;
    public static final int MAX_PROGRESS = MAX_TENTHS - MIN_TENTHS;

    private PlaybackSpeedRange() { }

    public static float normalize(float speed) {
        if (!Float.isFinite(speed)) {
            return 1f;
        }
        return Math.max(MIN_TENTHS, Math.min(MAX_TENTHS, Math.round(speed * 10f))) / 10f;
    }

    public static int progress(float speed) {
        return Math.round(normalize(speed) * 10f) - MIN_TENTHS;
    }

    public static float speed(int progress) {
        return (MIN_TENTHS + Math.max(0, Math.min(MAX_PROGRESS, progress))) / 10f;
    }

    public static String label(float speed) {
        return String.format(Locale.ROOT, "%.1f×", normalize(speed));
    }
}
