package com.canace.x3offlinereader.builder.audio;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public final class WaveSupport {
    private WaveSupport() {}

    public static long durationMs(Path wave) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(wave.toFile())) {
            return Math.round(input.getFrameLength() * 1000.0 / input.getFormat().getFrameRate());
        }
    }

    public static long concatenate(List<Path> waves, int pauseMs, Path output) throws Exception {
        if (waves.isEmpty()) throw new IllegalArgumentException("No sentence WAV files");
        List<AudioInputStream> opened = new ArrayList<>();
        List<InputStream> streams = new ArrayList<>();
        AudioFormat format;
        long frames = 0;
        try {
            AudioInputStream first = AudioSystem.getAudioInputStream(waves.get(0).toFile());
            opened.add(first);
            format = first.getFormat();
            streams.add(first);
            frames += first.getFrameLength();
            long pauseFrames = Math.round(format.getFrameRate() * pauseMs / 1000.0);
            int pauseBytes = Math.toIntExact(pauseFrames * format.getFrameSize());
            for (int i = 1; i < waves.size(); i++) {
                if (pauseBytes > 0) { streams.add(new ByteArrayInputStream(new byte[pauseBytes])); frames += pauseFrames; }
                AudioInputStream next = AudioSystem.getAudioInputStream(waves.get(i).toFile());
                opened.add(next);
                if (!format.matches(next.getFormat())) throw new IllegalArgumentException("TTS WAV formats differ");
                streams.add(next);
                frames += next.getFrameLength();
            }
            try (SequenceInputStream sequence = new SequenceInputStream(Collections.enumeration(streams));
                 AudioInputStream combined = new AudioInputStream(sequence, format, frames)) {
                AudioSystem.write(combined, AudioFileFormat.Type.WAVE, output.toFile());
            }
            return Math.round(frames * 1000.0 / format.getFrameRate());
        } finally {
            for (AudioInputStream stream : opened) try { stream.close(); } catch (Exception ignored) {}
        }
    }
}
