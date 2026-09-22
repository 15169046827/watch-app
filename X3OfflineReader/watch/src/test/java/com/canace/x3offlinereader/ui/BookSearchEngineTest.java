package com.canace.x3offlinereader.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;

final class BookSearchEngineTest {
    @Test
    void matchesChineseExactlyAndAsciiWithoutCase() {
        String text = "第一句 HeLLo 世界，第二句是目标。";
        assertEquals(4, BookSearchEngine.indexOf(text, "hello", 0));
        assertEquals(text.indexOf("目标"), BookSearchEngine.indexOf(text, "目标", 0));
        assertEquals(-1, BookSearchEngine.indexOf(text, "不存在", 0));
    }

    @Test
    void formatsResultAndBoundsSnippet() {
        BookSearchEngine.Result result =
                new BookSearchEngine.Result("c", "第一章", "t", 500, 3723500, "…公开上下文…");
        assertEquals("第一章 · 1:02:03\n…公开上下文…", result.toString());
        String text =
                String.join("", Collections.nCopies(40, "前"))
                        + "目标"
                        + String.join("", Collections.nCopies(45, "后"));
        String snippet = BookSearchEngine.snippet(text, text.indexOf("目标"), text.indexOf("目标") + 2);
        assertTrue(snippet.startsWith("…"));
        assertTrue(snippet.endsWith("…"));
        assertTrue(snippet.contains("目标"));
    }

    @Test
    void rejectsEmptyAndOverlongQueries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BookSearchEngine.search(Collections.emptyList(), " "));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BookSearchEngine.search(
                                Collections.emptyList(),
                                String.join("", Collections.nCopies(65, "字"))));
    }
}
