package com.canace.x3offlinereader.builder.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.canace.x3offlinereader.builder.model.BookModel;
import org.junit.jupiter.api.Test;

final class SentenceSegmenterTest {
    @Test void usesUnicodeCodePointOffsets() {
        BookModel.Chapter chapter = new BookModel.Chapter(); chapter.chapterId = "chapter-001-001";
        BookModel.Block block = new BookModel.Block(); block.blockId = "block"; block.text = "字符𠀀。下一句！"; chapter.blocks.add(block);
        var segments = new SentenceSegmenter().segment(chapter);
        assertEquals(2, segments.size());
        assertEquals(4, segments.get(0).endChar);
        assertEquals("字符𠀀。", segments.get(0).text);
        assertEquals(4, segments.get(1).startChar);
        assertEquals(8, segments.get(1).endChar);
    }
}
