package com.canace.x3offlinereader.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LrcReaderTest {
    @TempDir Path temporary;

    @Test
    void parsesFractionWidthsAndSortsLines() throws Exception {
        Path lrc = temporary.resolve("sample.lrc");
        Files.write(
                lrc,
                "[00:02.500]later\n[00:01.5]first\n[00:03]last\n".getBytes(StandardCharsets.UTF_8));
        LrcReader reader = new LrcReader(lrc.toString());
        assertEquals("", reader.at(1499));
        assertEquals("first", reader.at(1500));
        assertEquals("later", reader.at(2500));
        assertEquals("last", reader.at(3000));
    }

    @Test
    void missingFileIsAnEmptyLyric() {
        assertEquals("", new LrcReader(temporary.resolve("missing.lrc").toString()).at(5000));
    }

    @Test
    void decodesGb18030ChineseLyrics() throws Exception {
        Path lrc = temporary.resolve("gbk.lrc");
        Files.write(lrc, "[00:01.00]中文歌词\n".getBytes(Charset.forName("GB18030")));
        assertEquals("中文歌词", new LrcReader(lrc.toString()).at(1000));
    }

    @Test
    void expandsMultipleTimeTagsAndAppliesOffset() throws Exception {
        Path lrc = temporary.resolve("multiple.lrc");
        Files.write(
                lrc, "[offset:250]\n[00:01.00][00:02.00]repeat\n".getBytes(StandardCharsets.UTF_8));
        LrcReader reader = new LrcReader(lrc.toString());
        assertEquals("", reader.at(1249));
        assertEquals("repeat", reader.at(1250));
        assertEquals("repeat", reader.at(2250));
    }

    @Test
    void collapsesEnhancedInlineWordTimestampsIntoOneVisibleLine() throws Exception {
        Path lrc = temporary.resolve("enhanced.lrc");
        Files.write(
                lrc,
                "[ti:公开测试]\r\n[ar:X3]\r\n[00:01.00]逐[00:01.20]字[00:01.40]歌词[00:02.00]\r\n"
                        .getBytes(StandardCharsets.UTF_8));
        LrcReader reader = new LrcReader(lrc.toString());
        assertEquals("", reader.at(999));
        assertEquals("逐字歌词", reader.at(1000));
        assertEquals("逐字歌词", reader.at(1999));
    }

    @Test
    void returnsDistinctPreviousCurrentAndNextLines() throws Exception {
        Path lrc = temporary.resolve("window.lrc");
        Files.write(
                lrc,
                "[00:00.00]上一句\n[00:01.00][00:02.00]当前句\n[00:03.00]下一句\n"
                        .getBytes(StandardCharsets.UTF_8));
        LrcReader.Window window = new LrcReader(lrc.toString()).window(2250);
        assertEquals("上一句", window.previous);
        assertEquals("当前句", window.current);
        assertEquals("下一句", window.next);
    }

    @Test
    void exposesFullScrollableLyricsAndActiveOffsets() throws Exception {
        Path lrc = temporary.resolve("full.lrc");
        Files.write(
                lrc,
                ("[00:00.00]第一句\n[00:01.00][00:02.00]第二句\n[00:03.00]第三句\n"
                                + "[00:04.00]第四句\n[00:05.00]第五句\n[00:06.00]第六句\n"
                                + "[00:07.00]第七句\n[00:08.00]第八句\n")
                        .getBytes(StandardCharsets.UTF_8));
        LrcReader reader = new LrcReader(lrc.toString());
        assertEquals("第一句\n\n第二句\n\n第三句\n\n第四句\n\n第五句\n\n第六句\n\n第七句\n\n第八句", reader.fullText());
        assertEquals(1, reader.activeIndex(2500));
        assertEquals(
                "第二句", reader.fullText().substring(reader.activeStart(1), reader.activeEnd(1)));
    }
}
