package com.canace.x3wolainotes;

public final class NoteFormatterSelfTest {
    public static void main(String[] args) {
        require("hello world".equals(NoteFormatter.normalize("  hello   world  ")), "normalize spaces");
        require("☐ 买牛奶".equals(NoteFormatter.forWolai(NoteItem.KIND_TODO, "买牛奶")), "todo prefix");
        require("记录".equals(NoteFormatter.forWolai(NoteItem.KIND_NOTE, "记录")), "note unchanged");
        require(NoteFormatter.normalize(repeat('a', 260)).length() == AppConfig.MAX_NOTE_LENGTH,
                "length cap");
        System.out.println("NoteFormatterSelfTest OK");
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void require(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}

