package com.canace.x3offlinereader.builder.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.canace.x3offlinereader.builder.model.BookModel;
import org.junit.jupiter.api.Test;

final class SentenceSegmenterTest {
    @Test
    void usesUnicodeCodePointOffsets() {
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = "chapter-001-001";
        BookModel.Block block = new BookModel.Block();
        block.blockId = "block";
        block.text = "字符𠀀。下一句！";
        chapter.blocks.add(block);
        var segments = new SentenceSegmenter().segment(chapter);
        assertEquals(2, segments.size());
        assertEquals(4, segments.get(0).endChar);
        assertEquals("字符𠀀。", segments.get(0).text);
        assertEquals(4, segments.get(1).startChar);
        assertEquals(8, segments.get(1).endChar);
    }

    @Test
    void excludesTrimmedLineBreaksFromTheDeclaredRange() {
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = "chapter-001-001";
        BookModel.Block block = new BookModel.Block();
        block.blockId = "block";
        block.text = "第一句。\n  第二句！\n";
        chapter.blocks.add(block);
        var segments = new SentenceSegmenter().segment(chapter);
        assertEquals(2, segments.size());
        assertEquals("第一句。", segments.get(0).text);
        assertEquals(4, segments.get(0).endChar);
        assertEquals("第二句！", segments.get(1).text);
        assertEquals(7, segments.get(1).startChar);
        assertEquals(11, segments.get(1).endChar);
    }

    @Test
    void keepsUnicodeWhitespaceOutOfBothTextAndRange() {
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = "chapter-001-001";
        BookModel.Block block = new BookModel.Block();
        block.blockId = "block";
        block.text = "甲。　乙！";
        chapter.blocks.add(block);
        var segments = new SentenceSegmenter().segment(chapter);
        assertEquals("乙！", segments.get(1).text);
        assertEquals(3, segments.get(1).startChar);
        assertEquals(5, segments.get(1).endChar);
    }

    @Test
    void keepsClosingQuotesAndBracketsWithTheSentenceTheyClose() {
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = "chapter-001-001";
        BookModel.Block block = new BookModel.Block();
        block.blockId = "block";
        block.text = "“你好。”（下一句！）";
        chapter.blocks.add(block);
        var segments = new SentenceSegmenter().segment(chapter);
        assertEquals(2, segments.size());
        assertEquals("“你好。”", segments.get(0).text);
        assertEquals(0, segments.get(0).startChar);
        assertEquals(5, segments.get(0).endChar);
        assertEquals("（下一句！）", segments.get(1).text);
        assertEquals(5, segments.get(1).startChar);
        assertEquals(11, segments.get(1).endChar);
    }

    @Test
    void dropsPunctuationOnlyBlocksInsteadOfSendingThemToTts() {
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = "chapter-001-001";
        BookModel.Block punctuation = new BookModel.Block();
        punctuation.blockId = "punctuation";
        punctuation.text = "”……";
        chapter.blocks.add(punctuation);
        BookModel.Block words = new BookModel.Block();
        words.blockId = "words";
        words.text = "正文。";
        chapter.blocks.add(words);
        var segments = new SentenceSegmenter().segment(chapter);
        assertEquals(1, segments.size());
        assertEquals("正文。", segments.get(0).text);
    }
}
