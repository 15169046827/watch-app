package com.canace.x3offlinereader.builder;

import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.canace.x3offlinereader.builder.tts.EdgeTtsProvider;
import com.canace.x3offlinereader.builder.tts.MeloTtsProvider;
import com.canace.x3offlinereader.builder.tts.MeloTtsProviderPool;
import com.canace.x3offlinereader.builder.tts.SherpaOnnxTtsProvider;
import com.canace.x3offlinereader.builder.tts.SherpaOnnxTtsProviderPool;
import com.canace.x3offlinereader.builder.tts.ToneTtsProvider;
import com.canace.x3offlinereader.builder.tts.WindowsSapiTtsProvider;
import com.canace.x3offlinereader.builder.tts.WindowsSapiTtsProviderPool;
import java.nio.file.Path;
import java.util.List;

/** One source of truth for stable provider IDs and user-facing offline/online labels. */
public final class TtsProviderCatalog {
    public record Entry(
            String id,
            String label,
            boolean online,
            String defaultVoice,
            long serialCharactersPerHour,
            long optimizedCharactersPerHour,
            int optimizedParallelism) {
        public String throughputLabel() {
            return optimizedCharactersPerHour <= 0
                    ? ""
                    : String.format("本机长篇实测约 %.1f 万字/小时", optimizedCharactersPerHour / 10_000.0);
        }

        public long estimatedCharactersPerHour(int parallelism) {
            if (optimizedCharactersPerHour <= 0) {
                return 3_600_000;
            }
            int safe = Math.max(1, parallelism);
            if (safe >= optimizedParallelism || optimizedParallelism <= 1) {
                return optimizedCharactersPerHour;
            }
            double progress = (safe - 1.0) / (optimizedParallelism - 1.0);
            return Math.round(
                    serialCharactersPerHour
                            + (optimizedCharactersPerHour - serialCharactersPerHour) * progress);
        }

        @Override
        public String toString() {
            return throughputLabel().isBlank() ? label : label + " · " + throughputLabel();
        }
    }

    private static final List<Entry> ENTRIES =
            List.of(
                    new Entry("melo", "[离线] MeloTTS 中文", false, "ZH", 15_356, 73_539, 2),
                    new Entry(
                            "sherpa-kokoro",
                            "[离线] Kokoro 中文（多音色）",
                            false,
                            "中文女声 zf_001 · sid=3",
                            9_211,
                            83_036,
                            4),
                    new Entry(
                            "sherpa-aishell3",
                            "[离线·轻量] AISHELL-3（多音色）",
                            false,
                            "说话人 000 · sid=0",
                            47_366,
                            464_508,
                            4),
                    new Entry(
                            "windows",
                            "[离线] Windows 系统语音",
                            false,
                            "Microsoft Huihui Desktop",
                            360_119,
                            1_082_532,
                            2),
                    new Entry(
                            "edge",
                            "[在线] Microsoft Edge 中文",
                            true,
                            "zh-CN-XiaoxiaoNeural",
                            65_180,
                            833_152,
                            8),
                    new Entry("tone", "[测试] 固定音调（非自然语音）", false, "tone", 3_600_000, 3_600_000, 1));

    private TtsProviderCatalog() { }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * Test-only tone remains available to CLI/Golden tests but is never presented as a human voice.
     */
    public static List<Entry> guiEntries() {
        return ENTRIES.stream().filter(entry -> !"tone".equals(entry.id())).toList();
    }

    public static Entry entry(String id) {
        return ENTRIES.stream()
                .filter(e -> e.id.equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown TTS provider: " + id));
    }

    public static boolean supports(String id) {
        return ENTRIES.stream().anyMatch(e -> e.id.equalsIgnoreCase(id));
    }

    public static TtsProvider previewProvider(String id, Path root, Path meloPython) {
        return switch (entry(id).id) {
            case "melo" -> new MeloTtsProvider(root, meloPython);
            case "sherpa-kokoro" ->
                    new SherpaOnnxTtsProvider(root, SherpaOnnxTtsProvider.Model.KOKORO);
            case "sherpa-aishell3" ->
                    new SherpaOnnxTtsProvider(root, SherpaOnnxTtsProvider.Model.AISHELL3);
            case "windows" -> new WindowsSapiTtsProvider(root);
            case "edge" -> new EdgeTtsProvider(root);
            case "tone" -> new ToneTtsProvider();
            default -> throw new IllegalArgumentException("Unsupported TTS provider: " + id);
        };
    }

    public static TtsProvider buildProvider(BuilderOptions options, Path root) {
        if ("melo".equalsIgnoreCase(options.tts)) {
            return new MeloTtsProviderPool(root, options.meloPython, options.meloWorkers);
        }
        if ("sherpa-kokoro".equalsIgnoreCase(options.tts)) {
            return new SherpaOnnxTtsProviderPool(
                    root, SherpaOnnxTtsProvider.Model.KOKORO, options.sherpaWorkers);
        }
        if ("sherpa-aishell3".equalsIgnoreCase(options.tts)) {
            return new SherpaOnnxTtsProviderPool(
                    root, SherpaOnnxTtsProvider.Model.AISHELL3, options.sherpaWorkers);
        }
        if ("windows".equalsIgnoreCase(options.tts)) {
            return new WindowsSapiTtsProviderPool(root, options.windowsWorkers);
        }
        if ("edge".equalsIgnoreCase(options.tts)) {
            return new EdgeTtsProvider(root, options.edgeWorkers);
        }
        return previewProvider(options.tts, root, options.meloPython);
    }
}
