package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.audio.FfmpegAudioEncoder;
import com.canace.x3offlinereader.builder.audio.WaveSupport;
import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Online Chinese voices exposed by Microsoft Edge. No account or API key is stored. */
public final class EdgeTtsProvider implements TtsProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FALLBACK = List.of("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural", "zh-CN-XiaoyiNeural");
    private final Path root;
    public EdgeTtsProvider(Path root) { this.root = root.toAbsolutePath().normalize(); }
    @Override public String id() { return "edge-tts-online"; }
    @Override public boolean isAvailable() { return Files.isRegularFile(python()) && Files.isRegularFile(worker()); }
    @Override public List<String> voices() {
        if (!isAvailable()) return List.of();
        try {
            JsonNode response = run(Map.of("action", "voices"), 45);
            List<String> result = new ArrayList<>(); response.path("voices").forEach(node -> result.add(node.asText()));
            result.sort(Comparator.naturalOrder()); return result.isEmpty() ? FALLBACK : result;
        } catch (Exception ignored) { return FALLBACK; }
    }
    @Override public SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory) throws Exception {
        if (!isAvailable()) throw new IllegalStateException("在线语音组件未安装");
        Files.createDirectories(workingDirectory); Path mp3 = workingDirectory.resolve(request.getSegmentId() + ".edge.mp3").toAbsolutePath(); Path wav = workingDirectory.resolve(request.getSegmentId() + ".wav").toAbsolutePath();
        Files.deleteIfExists(mp3); Files.deleteIfExists(wav);
        Exception failure=null;for(int attempt=1;attempt<=2;attempt++){try{run(Map.of("action", "synthesize", "voice", request.getVoiceId(), "rate", request.getRate(), "text", request.getText(), "output", mp3.toString()), 12 * 60);if(Files.isRegularFile(mp3)&&Files.size(mp3)>0){failure=null;break;}throw new IllegalStateException("在线语音没有返回音频");}catch(Exception current){failure=current;Files.deleteIfExists(mp3);}}
        if(failure!=null)throw new IllegalStateException("在线语音当前句自动重试一次后仍失败："+failure.getMessage(),failure);
        new FfmpegAudioEncoder(null, root).decodeToWave(mp3, wav); Files.deleteIfExists(mp3);
        return new SynthesisResult(wav, WaveSupport.durationMs(wav));
    }
    private JsonNode run(Map<String, ?> request, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(python().toString(), worker().toString());
        Path packages = root.resolve(".tools/edge-tts/site-packages"); builder.environment().put("PYTHONPATH", packages.toString()); builder.environment().put("SSL_CERT_FILE", packages.resolve("certifi/cacert.pem").toString()); builder.environment().put("PYTHONUTF8", "1");
        Process process = builder.redirectErrorStream(false).start();
        process.getOutputStream().write(JSON.writeValueAsBytes(request)); process.getOutputStream().close();
        boolean ended = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!ended) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); throw new IllegalStateException("在线语音服务长时间没有响应"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("在线语音失败：" + sanitize(error));
        return JSON.readTree(output);
    }
    private Path python() { Path portable = root.resolve(".tools/melo/python.exe"); if (Files.isRegularFile(portable)) return portable; return Path.of("python.exe"); }
    private Path worker() { return root.resolve("builder/edge/worker.py"); }
    private static String sanitize(String text) { String clean = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").strip(); return clean.isBlank() ? "服务未返回诊断" : clean.length() > 300 ? clean.substring(clean.length() - 300) : clean; }
}
