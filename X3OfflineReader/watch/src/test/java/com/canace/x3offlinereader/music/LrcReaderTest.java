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

    @Test void parsesFractionWidthsAndSortsLines() throws Exception {
        Path lrc=temporary.resolve("sample.lrc");
        Files.write(lrc,"[00:02.500]later\n[00:01.5]first\n[00:03]last\n".getBytes(StandardCharsets.UTF_8));
        LrcReader reader=new LrcReader(lrc.toString());
        assertEquals("",reader.at(1499));
        assertEquals("first",reader.at(1500));
        assertEquals("later",reader.at(2500));
        assertEquals("last",reader.at(3000));
    }

    @Test void missingFileIsAnEmptyLyric() {
        assertEquals("",new LrcReader(temporary.resolve("missing.lrc").toString()).at(5000));
    }

    @Test void decodesGb18030ChineseLyrics() throws Exception {
        Path lrc=temporary.resolve("gbk.lrc");
        Files.write(lrc,"[00:01.00]中文歌词\n".getBytes(Charset.forName("GB18030")));
        assertEquals("中文歌词",new LrcReader(lrc.toString()).at(1000));
    }

    @Test void expandsMultipleTimeTagsAndAppliesOffset() throws Exception {
        Path lrc=temporary.resolve("multiple.lrc");
        Files.write(lrc,"[offset:250]\n[00:01.00][00:02.00]repeat\n".getBytes(StandardCharsets.UTF_8));
        LrcReader reader=new LrcReader(lrc.toString());
        assertEquals("",reader.at(1249));
        assertEquals("repeat",reader.at(1250));
        assertEquals("repeat",reader.at(2250));
    }
}
