package com.canace.x3offlinereader.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
