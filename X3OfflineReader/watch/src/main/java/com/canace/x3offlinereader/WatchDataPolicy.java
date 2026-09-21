package com.canace.x3offlinereader;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class WatchDataPolicy {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_BACKUP_BYTES = 4 * 1024 * 1024;
    static final int MAX_ITEMS = 10_000;
    static final int MAX_ID_LENGTH = 300;
    static final int MAX_PATH_LENGTH = 2_048;
    private static final Set<String> MUSIC_MODES = new HashSet<>(Arrays.asList(
            "stop_after_track", "repeat_one", "repeat_all", "shuffle"));

    static boolean validId(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_ID_LENGTH
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\0') < 0;
    }

    static boolean validPath(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_PATH_LENGTH
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\0') < 0;
    }

    static boolean validNovelSetting(String key, String value) {
        if (!"playback_speed".equals(key)) {
            return false;
        }
        try {
            float speed = Float.parseFloat(value);
            return speed >= 0.5f && speed <= 5.0f;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean validMusicSetting(String key, String value) {
        return validNovelSetting(key, value)
                || "playback_mode".equals(key) && MUSIC_MODES.contains(value);
    }

    private WatchDataPolicy() {
    }
}
