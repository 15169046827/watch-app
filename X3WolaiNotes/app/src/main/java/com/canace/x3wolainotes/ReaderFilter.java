package com.canace.x3wolainotes;

import java.util.Locale;

final class ReaderFilter {
    static final int MAX_QUERY_LENGTH = 40;

    private ReaderFilter() {
    }

    static String normalize(String query) {
        if (query == null) return "";
        String value = query.trim().replaceAll("\\s+", " ");
        return value.length() <= MAX_QUERY_LENGTH
                ? value : value.substring(0, MAX_QUERY_LENGTH);
    }

    static boolean matches(String query, String... values) {
        String normalized = normalize(query).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        StringBuilder haystack = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isEmpty()) continue;
                if (haystack.length() > 0) haystack.append(' ');
                haystack.append(value.toLowerCase(Locale.ROOT));
            }
        }
        String searchable = haystack.toString();
        for (String term : normalized.split(" ")) {
            if (!searchable.contains(term)) return false;
        }
        return true;
    }
}
