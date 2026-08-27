package com.canace.x3offlinereader.builder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class BuilderOptions {
    public final List<Path> inputs = new ArrayList<>();
    public Path output;
    public String title;
    public String author = "";
    public String language = "zh-CN";
    public String tts = "melo";
    public String voice = "ZH";
    public Path meloPython;
    public int meloWorkers = 2;
    public double rate = 1.0;
    public int pauseMs = 250;
    public int maxTrackMinutes=20;
    public Path ffmpeg;
    public Path cover;
    public Instant createdAt = Instant.now();
    public boolean separate;
    public boolean outputToSource;
    public boolean allowOnlineTts;
}
