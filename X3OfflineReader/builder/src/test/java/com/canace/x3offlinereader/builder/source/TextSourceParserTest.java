package com.canace.x3offlinereader.builder.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TextSourceParserTest {
    @TempDir Path temp;

    @Test
    void splitsChineseChapterHeadings() throws Exception {
        Path file = temp.resolve("book.txt");
        Files.writeString(file, "第一章 开始\n第一句。\n\n第二章 继续\n第二句！", StandardCharsets.UTF_8);
        var volume = new TextSourceParser().parse(file, 0);
        assertEquals(2, volume.chapters.size());
        assertEquals("第一章 开始", volume.chapters.get(0).title);
        assertEquals("第二章 继续", volume.chapters.get(1).title);
    }
}
