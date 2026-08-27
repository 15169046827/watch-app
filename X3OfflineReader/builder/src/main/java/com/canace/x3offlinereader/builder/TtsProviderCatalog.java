package com.canace.x3offlinereader.builder;

import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.canace.x3offlinereader.builder.tts.EdgeTtsProvider;
import com.canace.x3offlinereader.builder.tts.MeloTtsProvider;
import com.canace.x3offlinereader.builder.tts.MeloTtsProviderPool;
import com.canace.x3offlinereader.builder.tts.SherpaOnnxTtsProvider;
import com.canace.x3offlinereader.builder.tts.ToneTtsProvider;
import com.canace.x3offlinereader.builder.tts.WindowsSapiTtsProvider;
import java.nio.file.Path;
import java.util.List;

/** One source of truth for stable provider IDs and user-facing offline/online labels. */
public final class TtsProviderCatalog {
    public record Entry(String id, String label, boolean online, String defaultVoice) {
        @Override public String toString() { return label; }
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("melo", "[离线] MeloTTS 中文", false, "ZH"),
            new Entry("sherpa-kokoro", "[离线] Kokoro 中文（多音色）", false, "中文女声 zf_001 · sid=3"),
            new Entry("sherpa-aishell3", "[离线·轻量] AISHELL-3（多音色）", false, "说话人 000 · sid=0"),
            new Entry("windows", "[离线] Windows 系统语音", false, "Microsoft Huihui Desktop - Chinese (Simplified)"),
            new Entry("edge", "[在线] Microsoft Edge 中文", true, "zh-CN-XiaoxiaoNeural"),
            new Entry("tone", "[测试] 固定音调（非自然语音）", false, "tone"));

    private TtsProviderCatalog() {}

    public static List<Entry> entries() { return ENTRIES; }
    /** Test-only tone remains available to CLI/Golden tests but is never presented as a human voice. */
    public static List<Entry> guiEntries() { return ENTRIES.stream().filter(entry -> !"tone".equals(entry.id())).toList(); }
    public static Entry entry(String id) { return ENTRIES.stream().filter(e -> e.id.equalsIgnoreCase(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown TTS provider: " + id)); }
    public static boolean supports(String id) { return ENTRIES.stream().anyMatch(e -> e.id.equalsIgnoreCase(id)); }

    public static TtsProvider previewProvider(String id, Path root, Path meloPython) {
        return switch (entry(id).id) {
            case "melo" -> new MeloTtsProvider(root, meloPython);
            case "sherpa-kokoro" -> new SherpaOnnxTtsProvider(root, SherpaOnnxTtsProvider.Model.KOKORO);
            case "sherpa-aishell3" -> new SherpaOnnxTtsProvider(root, SherpaOnnxTtsProvider.Model.AISHELL3);
            case "windows" -> new WindowsSapiTtsProvider();
            case "edge" -> new EdgeTtsProvider(root);
            case "tone" -> new ToneTtsProvider();
            default -> throw new IllegalArgumentException("Unsupported TTS provider: " + id);
        };
    }

    public static TtsProvider buildProvider(BuilderOptions options, Path root) {
        if ("melo".equalsIgnoreCase(options.tts)) return new MeloTtsProviderPool(root, options.meloPython, options.meloWorkers);
        return previewProvider(options.tts, root, options.meloPython);
    }
}
