package com.canace.x3offlinereader.ui;

import java.util.regex.Pattern;

/** Keeps source-authored chapter numbers authoritative and numbers only plain titles. */
public final class ChapterLabels {
    private static final Pattern NUMBERED =
            Pattern.compile("^第\\s*(?:[0-9]+|[〇零一二三四五六七八九十百千万两]+)\\s*[章节回卷部篇]");

    private ChapterLabels() { }

    public static String numbered(int ordinal, String title) {
        String value = title == null ? "" : title.trim();
        if (NUMBERED.matcher(value).find()) {
            return value;
        }
        return "第" + Math.max(1, ordinal) + "章" + (value.isEmpty() ? "" : " " + value);
    }
}
