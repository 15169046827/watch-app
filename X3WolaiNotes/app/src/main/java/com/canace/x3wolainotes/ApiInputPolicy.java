package com.canace.x3wolainotes;

import java.util.regex.Pattern;

final class ApiInputPolicy {
    static final int MAX_RESOURCE_ID_LENGTH = 200;
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_RESOURCE_ID_LENGTH + "}");

    private ApiInputPolicy() {
    }

    static boolean isValidResourceId(String value) {
        return value != null && RESOURCE_ID.matcher(value).matches();
    }

    static String boundedText(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
