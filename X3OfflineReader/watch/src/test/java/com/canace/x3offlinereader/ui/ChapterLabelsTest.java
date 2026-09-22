package com.canace.x3offlinereader.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ChapterLabelsTest {
    @Test
    void preservesSourceArabicChapterNumber() {
        assertEquals("第33章 风雪夜", ChapterLabels.numbered(34, "第33章 风雪夜"));
    }

    @Test
    void preservesSourceChineseChapterNumber() {
        assertEquals("第三十三章 风雪夜", ChapterLabels.numbered(34, "第三十三章 风雪夜"));
    }

    @Test
    void numbersPlainTitlesByLogicalOrder() {
        assertEquals("第2章 风雪夜", ChapterLabels.numbered(2, "风雪夜"));
    }

    @Test
    void numbersUntitledChapters() {
        assertEquals("第1章", ChapterLabels.numbered(0, "  "));
    }
}
