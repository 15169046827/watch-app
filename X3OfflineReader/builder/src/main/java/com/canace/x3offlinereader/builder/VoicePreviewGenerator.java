package com.canace.x3offlinereader.builder;

import com.canace.x3offlinereader.builder.audio.WaveSupport;
import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Build-time generator for short, click-to-play natural-voice samples. */
public final class VoicePreviewGenerator {

    private VoicePreviewGenerator() { }

    public static void main(String[] args) throws Exception {
        Path root = BuilderMain.findProjectRoot();
        Options options = parseOptions(args);
        if (options.indexOnly) {
            writeIndex(root);
            System.out.println("Voice preview index updated.");
            return;
        }
        options.validateShard();
        Summary summary = new Summary();
        for (TtsProviderCatalog.Entry entry : TtsProviderCatalog.guiEntries()) {
            if (options.selected(entry)) {
                generateProvider(root, entry, options, summary);
            }
        }
        finish(root, options, summary);
    }

    private static Options parseOptions(String[] args) {
        Options result = new Options();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            switch (key) {
                case "--provider" -> result.only = requireValue(args, ++index, key);
                case "--overwrite" -> result.overwrite = true;
                case "--index-only" -> result.indexOnly = true;
                case "--shard-count" ->
                        result.shardCount = Integer.parseInt(requireValue(args, ++index, key));
                case "--shard-index" ->
                        result.shardIndex = Integer.parseInt(requireValue(args, ++index, key));
                default -> throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        return result;
    }

    private static String requireValue(String[] args, int index, String key) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + key);
        }
        return args[index];
    }

    private static void generateProvider(
            Path root,
            TtsProviderCatalog.Entry entry,
            Options options,
            Summary summary)
            throws Exception {
        TtsProvider provider = TtsProviderCatalog.previewProvider(entry.id(), root, null);
        try {
            if (!provider.isAvailable()) {
                summary.failures.add(entry.id() + ": provider unavailable");
                return;
            }
            List<String> voices = provider.voices();
            summary.total += voices.size();
            for (int index = 0; index < voices.size(); index++) {
                generateVoice(root, entry, provider, voices, index, options, summary);
            }
        } finally {
            close(provider);
        }
    }

    private static void generateVoice(
            Path root,
            TtsProviderCatalog.Entry entry,
            TtsProvider provider,
            List<String> voices,
            int index,
            Options options,
            Summary summary) {
        int number = index + 1;
        if ((number - 1) % options.shardCount != options.shardIndex) {
            return;
        }
        String voice = voices.get(index);
        Path target = VoicePreviewLibrary.path(root, entry.id(), voice);
        if (!options.overwrite && VoicePreviewLibrary.available(root, entry.id(), voice)) {
            summary.skipped++;
            return;
        }
        try {
            long duration = synthesizePreview(root, entry, provider, voice, target, options);
            summary.generated++;
            System.out.printf(
                    Locale.ROOT,
                    "[%s %d/%d] %s (%d ms)%n",
                    entry.id(),
                    number,
                    voices.size(),
                    voice,
                    duration);
        } catch (Exception error) {
            summary.failures.add(
                    entry.id()
                            + " / "
                            + voice
                            + ": "
                            + error.getClass().getSimpleName()
                            + " "
                            + safe(error));
        }
    }

    private static long synthesizePreview(
            Path root,
            TtsProviderCatalog.Entry entry,
            TtsProvider provider,
            String voice,
            Path target,
            Options options)
            throws Exception {
        Path work =
                BuilderWorkDirectory.resolve(root).resolve("preview-generation").resolve(entry.id());
        Files.createDirectories(work);
        String id = "preview-" + VoicePreviewLibrary.key(entry.id(), voice);
        String sentence = VoicePreviewLibrary.sentence(entry.id());
        if (!options.overwrite && Files.isRegularFile(target)) {
            sentence =
                    VoicePreviewLibrary.adjustedSentence(
                            entry.id(), WaveSupport.durationMs(target));
        }
        SynthesisResult raw = synthesize(provider, id, sentence, voice, work);
        normalize(root, raw.getWavPath(), target);
        long duration = WaveSupport.durationMs(target);
        String adjusted = VoicePreviewLibrary.adjustedSentence(entry.id(), duration);
        if (!adjusted.equals(sentence)) {
            raw = synthesize(provider, id + "-adjusted", adjusted, voice, work);
            normalize(root, raw.getWavPath(), target);
            duration = WaveSupport.durationMs(target);
        }
        requireNaturalDuration(duration);
        return duration;
    }

    private static SynthesisResult synthesize(
            TtsProvider provider, String id, String text, String voice, Path work)
            throws Exception {
        return provider.synthesize(
                new SynthesisRequest(id, text, voice, VoicePreviewLibrary.RATE), work);
    }

    private static void requireNaturalDuration(long duration) {
        if (duration < 5000 || duration > 10000) {
            throw new IllegalStateException(
                    "natural duration "
                            + duration
                            + " ms; adjust preview text instead of audio speed");
        }
    }

    private static void close(TtsProvider provider) {
        if (provider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void finish(Path root, Options options, Summary summary) throws Exception {
        if (options.shardCount == 1) {
            writeIndex(root);
        }
        System.out.printf(
                Locale.ROOT,
                "Preview generation complete: total=%d generated=%d cached=%d failures=%d%n",
                summary.total,
                summary.generated,
                summary.skipped,
                summary.failures.size());
        if (!summary.failures.isEmpty()) {
            throw new IllegalStateException(
                    "Preview failures:\n" + String.join("\n", summary.failures));
        }
    }

    private static final class Options {

        String only;

        boolean overwrite;

        boolean indexOnly;

        int shardCount = 1;

        int shardIndex;

        void validateShard() {
            if (shardCount < 1 || shardIndex < 0 || shardIndex >= shardCount) {
                throw new IllegalArgumentException("Invalid shard configuration");
            }
        }

        boolean selected(TtsProviderCatalog.Entry entry) {
            return only == null || entry.id().equalsIgnoreCase(only);
        }
    }

    private static final class Summary {

        final List<String> failures = new ArrayList<>();

        int generated;

        int skipped;

        int total;
    }
    private static void normalize(Path root, Path source, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Path staging = target.resolveSibling(target.getFileName() + ".tmp.wav");
        Files.deleteIfExists(staging);
        List<String> command = new ArrayList<>();
        command.add(ffmpeg(root).toString());
        command.addAll(
                List.of("-hide_banner", "-loglevel", "error", "-y", "-i", source.toString()));
        command.addAll(
                List.of("-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", staging.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String diagnostic =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "FFmpeg preview normalization failed: " + diagnostic.strip());
        }
        Files.move(
                staging,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        if (!source.equals(target)) {
            Files.deleteIfExists(source);
        }
    }

    private static Path ffmpeg(Path root) throws Exception {
        try (var paths = Files.walk(root.resolve(".tools/ffmpeg"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("ffmpeg.exe"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static void writeIndex(Path root) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("revision\tprovider\tvoice\tfile\tdurationMs\tsentence");
        for (TtsProviderCatalog.Entry entry : TtsProviderCatalog.guiEntries()) {
            TtsProvider provider = TtsProviderCatalog.previewProvider(entry.id(), root, null);
            try {
                for (String voice : provider.voices()) {
                    Path path = VoicePreviewLibrary.path(root, entry.id(), voice);
                    if (VoicePreviewLibrary.available(root, entry.id(), voice)) {
                        lines.add(
                                String.join(
                                        "\t",
                                        VoicePreviewLibrary.REVISION,
                                        entry.id(),
                                        voice,
                                        path.getFileName().toString(),
                                        Long.toString(WaveSupport.durationMs(path)),
                                        VoicePreviewLibrary.sentence(entry.id())));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (provider instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        Path index = root.resolve("builder/voice-previews/index.tsv");
        Files.createDirectories(index.getParent());
        Files.write(index, lines, StandardCharsets.UTF_8);
    }

    private static String safe(Exception error) {
        String message = error.getMessage();
        return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
