package com.canace.x3offlinereader.builder.model;

import java.util.Objects;

/** Immutable input for one sentence-level synthesis operation. */
public final class SynthesisRequest {
    private final String segmentId;
    private final String text;
    private final String voiceId;
    private final double rate;

    public SynthesisRequest(String segmentId, String text, String voiceId, double rate) {
        this.segmentId = Objects.requireNonNull(segmentId, "segmentId");
        this.text = Objects.requireNonNull(text, "text");
        this.voiceId = Objects.requireNonNull(voiceId, "voiceId");
        this.rate = rate;
    }

    public String getSegmentId() { return segmentId; }
    public String getText() { return text; }
    public String getVoiceId() { return voiceId; }
    public double getRate() { return rate; }
}

