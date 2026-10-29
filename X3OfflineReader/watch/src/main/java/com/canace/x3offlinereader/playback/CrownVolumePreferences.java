package com.canace.x3offlinereader.playback;

import android.content.Context;

/** App-wide crown volume opt-out, separate from book/music playback settings. */
public final class CrownVolumePreferences {
    private static final String FILE = "audio_controls";

    private static final String ENABLED = "crown_volume_enabled";

    private CrownVolumePreferences() { }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, enabled).apply();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
