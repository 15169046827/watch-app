package com.canace.x3offlinereader.builder.spi;

import java.nio.file.Path;
import java.util.List;

/** Concatenates sentence WAV files and produces one AAC-in-M4A track. */
public interface AudioEncoder {
    void encodeM4a(List<Path> sentenceWavs, Path outputM4a) throws Exception;
}
