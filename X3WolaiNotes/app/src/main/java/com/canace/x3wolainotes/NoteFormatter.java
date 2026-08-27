package com.canace.x3wolainotes;

final class NoteFormatter {
    private NoteFormatter() {
    }

    static String normalize(String input) {
        if (input == null) return "";
        String value = input.trim().replaceAll("\\s+", " ");
        if (value.length() > AppConfig.MAX_NOTE_LENGTH) {
            value = value.substring(0, AppConfig.MAX_NOTE_LENGTH);
        }
        return value;
    }

    static String forWolai(int kind, String text) {
        String value = normalize(text);
        return kind == NoteItem.KIND_TODO ? "☐ " + value : value;
    }
}

