package com.canace.x3offlinereader.builder.spi;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import java.nio.file.Path;
import java.util.List;

/** Provider-neutral contract shared by MeloTTS and Windows local TTS. */
public interface TtsProvider {
    String id();

    boolean isAvailable();

    default List<String> voices() {
        return List.of();
    }

    default int parallelism() {
        return 1;
    }

    SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory) throws Exception;
}
