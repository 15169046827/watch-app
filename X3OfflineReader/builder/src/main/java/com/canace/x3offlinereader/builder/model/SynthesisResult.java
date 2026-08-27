package com.canace.x3offlinereader.builder.model;

import java.nio.file.Path;
import java.util.Objects;

/** Sentence PCM/WAV location and measured duration used to build the Timeline. */
public final class SynthesisResult {
    private final Path wavPath;
    private final long durationMs;

    public SynthesisResult(Path wavPath, long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        this.wavPath = Objects.requireNonNull(wavPath, "wavPath");
        this.durationMs = durationMs;
    }

    public Path getWavPath() { return wavPath; }
    public long getDurationMs() { return durationMs; }
}

