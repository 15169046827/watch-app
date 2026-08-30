package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.BuildControl;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Online Chinese voices exposed by Microsoft Edge. No account or API key is stored. */
public final class EdgeTtsProvider implements TtsProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FALLBACK = List.of("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural", "zh-CN-XiaoyiNeural");
    private static final long[] RETRY_DELAYS_MILLIS = {2_000L, 5_000L, 10_000L, 20_000L, 40_000L};
    private static final int MAX_ATTEMPTS = 25;
    @FunctionalInterface interface CheckedAction { void run() throws Exception; }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    static final class RecoveryExhaustedException extends Exception {
        private final int attempts;
        RecoveryExhaustedException(int attempts,Exception cause){super(cause);this.attempts=attempts;}
        int attempts(){return attempts;}
    }
    private final Path root;
    private final int parallelism;
    public EdgeTtsProvider(Path root) { this(root,1); }
    public EdgeTtsProvider(Path root,int parallelism) {
        if(parallelism<1||parallelism>12)throw new IllegalArgumentException("Edge parallelism must be 1..12");
        this.root=root.toAbsolutePath().normalize();this.parallelism=parallelism;
    }
    @Override public String id() { return "edge-tts-online"; }
    @Override public boolean isAvailable() { return Files.isRegularFile(python()) && Files.isRegularFile(worker()); }
    @Override public int parallelism(){return parallelism;}
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
        String speechText=spokenText(request.getText());
        try{
            runWithRecovery(()->{
                Files.deleteIfExists(mp3);
                run(Map.of("action", "synthesize", "voice", request.getVoiceId(), "rate", request.getRate(), "text", speechText, "output", mp3.toString()), 12 * 60);
                if(!Files.isRegularFile(mp3)||Files.size(mp3)==0)throw new IllegalStateException("在线语音没有返回音频");
            },millis->Thread.sleep(millis+ThreadLocalRandom.current().nextLong(250,1_501)),MAX_ATTEMPTS);
        }catch(RecoveryExhaustedException exhausted){
            throw new BuildControl.PauseRequestedException(temporaryPauseMessage(exhausted.attempts(),(Exception)exhausted.getCause()));
        }catch(InterruptedException interrupted){
            Files.deleteIfExists(mp3);throw interrupted;
        }catch(Exception failure){
            Files.deleteIfExists(mp3);throw new IllegalStateException("在线语音参数或配置错误："+failure.getMessage(),failure);
        }
        new FfmpegAudioEncoder(null, root).decodeToWave(mp3, wav); Files.deleteIfExists(mp3);
        return new SynthesisResult(wav, WaveSupport.durationMs(wav));
    }
    private JsonNode run(Map<String, ?> request, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(python().toString(), worker().toString());
        Path packages = root.resolve(".tools/edge-tts/site-packages"); builder.environment().put("PYTHONPATH", packages.toString()); builder.environment().put("SSL_CERT_FILE", packages.resolve("certifi/cacert.pem").toString()); builder.environment().put("PYTHONUTF8", "1");
        Process process = builder.redirectErrorStream(false).start();
        process.getOutputStream().write(JSON.writeValueAsBytes(request)); process.getOutputStream().close();
        boolean ended;
        try{ended=process.waitFor(timeoutSeconds,TimeUnit.SECONDS);}
        catch(InterruptedException interrupted){process.destroyForcibly();try{process.waitFor(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}throw interrupted;}
        if (!ended) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); throw new IllegalStateException("在线语音服务长时间没有响应"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("在线语音失败：" + sanitize(error));
        return JSON.readTree(output);
    }
    private Path python() { Path portable = root.resolve(".tools/melo/python.exe"); if (Files.isRegularFile(portable)) return portable; return Path.of("python.exe"); }
    private Path worker() { return root.resolve("builder/edge/worker.py"); }
    static boolean shouldRetry(Throwable failure) {
        StringBuilder diagnosis=new StringBuilder();
        for(Throwable current=failure;current!=null;current=current.getCause()){
            diagnosis.append(' ').append(String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT));
        }
        return Stream.of("noaudioreceived","no audio was received","connection","connectorerror","connectionreseterror","timeout","timed out","serverdisconnected","clientoserror","wssserverhandshakeerror","websocket","incompleteread","eoferror","temporarily","429","502","503","504","长时间没有响应").anyMatch(value->diagnosis.indexOf(value)>=0);
    }
    static int runWithRecovery(CheckedAction action,Sleeper sleeper,int maximumAttempts)throws Exception{
        for(int attempt=1;attempt<=maximumAttempts;attempt++){
            try{action.run();return attempt;}
            catch(Exception failure){
                if(!shouldRetry(failure))throw failure;
                if(attempt==maximumAttempts)throw new RecoveryExhaustedException(attempt,failure);
                sleeper.sleep(retryDelayMillis(attempt));
            }
        }
        throw new IllegalStateException("unreachable");
    }
    static long retryDelayMillis(int completedAttempts) { return completedAttempts<=RETRY_DELAYS_MILLIS.length?RETRY_DELAYS_MILLIS[completedAttempts-1]:60_000L; }
    static String spokenText(String text){
        String source=text.strip();
        boolean speakable=source.codePoints().anyMatch(cp->Character.isLetterOrDigit(cp)||Character.isIdeographic(cp)
                ||Character.getType(cp)==Character.LETTER_NUMBER||Character.getType(cp)==Character.OTHER_NUMBER);
        if(!speakable)throw new IllegalArgumentException("在线语音片段不含可发音文字或数字");
        return hasTerminalPunctuation(source)?source:source+"。";
    }
    private static boolean hasTerminalPunctuation(String text){
        int end=text.length();
        while(end>0){
            int cp=text.codePointBefore(end),type=Character.getType(cp);
            if(type==Character.END_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION){end-=Character.charCount(cp);continue;}
            return "。！？!?；;".indexOf(cp)>=0;
        }
        return false;
    }
    static String temporaryPauseMessage(int attempts,Throwable failure){return "Edge 在线语音持续恢复约 20 分钟仍不可用：当前句共尝试 "+attempts+" 次后仍未收到音频；已自动保存并暂停，稍后可从断点继续。诊断："+sanitize(failure==null?null:failure.getMessage());}
    static String sanitize(String text) {
        String clean=text==null?"":text.replaceAll("(?s)Exception ignored in: <function _ProactorBasePipeTransport\\.__del__.*?RuntimeError: Event loop is closed"," ").replaceAll("[\\r\\n]+"," ").replaceAll("\\s+"," ").strip();
        int structured=clean.lastIndexOf("{\"errorType\"");if(structured>=0)clean=clean.substring(structured);
        return clean.isBlank()?"服务未返回诊断":clean.length()>600?clean.substring(clean.length()-600):clean;
    }
}
