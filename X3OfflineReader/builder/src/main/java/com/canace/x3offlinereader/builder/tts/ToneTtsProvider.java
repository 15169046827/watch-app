package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Deterministic synthetic provider for tests and public Golden Samples; not a product voice. */
public final class ToneTtsProvider implements TtsProvider {
    @Override
    public String id() {
        return "tone-fixture-v1";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public java.util.List<String> voices() {
        return java.util.List.of("tone");
    }

    @Override
    public SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory)
            throws Exception {
        Files.createDirectories(workingDirectory);
        long durationMs =
                Math.max(
                        350,
                        Math.min(
                                5000,
                                request.getText().codePointCount(0, request.getText().length())
                                        * 130L));
        float sampleRate = 22050f;
        int frames = (int) (sampleRate * durationMs / 1000);
        byte[] pcm = new byte[frames * 2];
        double frequency = 330 + Math.abs(request.getSegmentId().hashCode() % 220);
        for (int frame = 0; frame < frames; frame++) {
            double envelope = Math.min(1.0, Math.min(frame / 220.0, (frames - frame) / 220.0));
            short sample =
                    (short)
                            (Math.sin(2 * Math.PI * frequency * frame / sampleRate)
                                    * 5000
                                    * envelope);
            pcm[frame * 2] = (byte) (sample & 0xff);
            pcm[frame * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        Path output = workingDirectory.resolve(request.getSegmentId() + ".wav");
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream audio =
                new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
        return new SynthesisResult(output, durationMs);
    }
}
