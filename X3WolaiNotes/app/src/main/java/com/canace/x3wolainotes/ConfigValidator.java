package com.canace.x3wolainotes;

import java.util.Locale;

final class ConfigValidator {
    private ConfigValidator() {
    }

    static boolean isConfigured(String value) {
        if (value == null) return false;
        String normalized = value.trim();
        if (normalized.isEmpty()) return false;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return !lower.startsWith("your_") && !lower.startsWith("replace_");
    }
}
