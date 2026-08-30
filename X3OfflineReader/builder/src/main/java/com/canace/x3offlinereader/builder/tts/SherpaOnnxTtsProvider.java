package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.audio.WaveSupport;
import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** CPU-only, fully offline Chinese TTS backed by the Apache-2.0 sherpa-onnx runtime. */
public final class SherpaOnnxTtsProvider implements TtsProvider,AutoCloseable {
    public enum Model { KOKORO, AISHELL3 }
    private static final Pattern SID = Pattern.compile("sid\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final String[] KOKORO_FEMALE = "001 002 003 004 005 006 007 008 017 018 019 021 022 023 024 026 027 028 032 036 038 039 040 042 043 044 046 047 048 049 051 059 060 067 070 071 072 073 074 075 076 077 078 079 083 084 085 086 087 088 090 092 093 094 099".split(" ");
    private static final String[] KOKORO_MALE = "009 010 011 012 013 014 015 016 020 025 029 030 031 033 034 035 037 041 045 050 052 053 054 055 056 057 058 061 062 063 064 065 066 068 069 080 081 082 089 091 095 096 097 098 100".split(" ");
    private final Path root;
    private final Model model;
    private final int workerThreads;
    private Set<Integer> supportedChinese;
    private final Deque<String> stderrTail=new ArrayDeque<>();
    private Process process;private BufferedReader output;private BufferedWriter input;private double workerRate=Double.NaN;

    public SherpaOnnxTtsProvider(Path root, Model model) { this(root,model,0); }
    public SherpaOnnxTtsProvider(Path root,Model model,int workerThreads){this.root=root.toAbsolutePath().normalize();this.model=model;this.workerThreads=workerThreads;}
    @Override public String id() { return model == Model.KOKORO ? "sherpa-kokoro-v1.1-zh-text-v2" : "sherpa-vits-aishell3-text-v2"; }
    @Override public boolean isAvailable() { try { locatePython(); locateWorker(); locatePythonPackage(); locateModelDirectory(); return true; } catch (Exception ignored) { return false; } }
    @Override public int parallelism() { return 1; }

    @Override public List<String> voices() {
        List<String> voices = new ArrayList<>();
        if (model == Model.KOKORO) {
            for (int index = 0; index < KOKORO_FEMALE.length; index++) voices.add("中文女声 zf_" + KOKORO_FEMALE[index] + " · sid=" + (index + 3));
            for (int index = 0; index < KOKORO_MALE.length; index++) voices.add("中文男声 zm_" + KOKORO_MALE[index] + " · sid=" + (index + 58));
        } else {
            for (int sid = 0; sid < 174; sid++) voices.add(String.format(Locale.ROOT, "说话人 %03d · sid=%d", sid, sid));
        }
        return voices;
    }

    @Override public synchronized SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory) throws Exception {
        Path directory = locateModelDirectory();
        Files.createDirectories(workingDirectory);
        Path output = workingDirectory.resolve(request.getSegmentId() + ".wav").toAbsolutePath();
        String text=normalizeText(request.getText(),directory);Exception failure=null;for(int attempt=1;attempt<=2;attempt++){try{requestSingle(output,text,parseSid(request.getVoiceId()),request.getRate(),directory);failure=null;break;}catch(Exception current){failure=current;restartWorker();}}
        if(failure!=null)throw new IllegalStateException("离线语音当前句自动重试一次后仍失败："+failure.getMessage(),failure);
        return new SynthesisResult(output, WaveSupport.durationMs(output));
    }

    static int parseSid(String voice) { Matcher matcher = SID.matcher(voice == null ? "" : voice); if (!matcher.find()) throw new IllegalArgumentException("声音 ID 缺少 sid：" + voice); return Integer.parseInt(matcher.group(1)); }
    private String normalizeText(String value,Path directory)throws Exception{
        if(supportedChinese==null){Set<Integer> supported=new HashSet<>();Path lexicon=find(directory,model==Model.KOKORO?"lexicon-zh.txt":"lexicon.txt");for(String line:Files.readAllLines(lexicon,StandardCharsets.UTF_8)){int space=line.indexOf(' ');String word=space<0?line:line.substring(0,space);word.codePoints().forEach(supported::add);}supportedChinese=supported;}
        String text=Normalizer.normalize(value,Normalizer.Form.NFKC);StringBuilder result=new StringBuilder();
        for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);String mapped=switch(cp){case '：','；','，','、','·','_'->",";case '。'->".";case '！'->"!";case '？'->"?";case '“','”','‘','’','（','）','《','》','【','】','「','」'->"'";case '—','–','～'->"-";default->null;};if(mapped!=null){result.append(mapped);continue;}if(cp>=0x4e00&&cp<=0x9fa5){if(supportedChinese.contains(cp))result.appendCodePoint(cp);else result.append(',');continue;}if(cp<128&&(Character.isLetterOrDigit(cp)||Character.isWhitespace(cp)||"!?…,.'-".indexOf(cp)>=0)){result.appendCodePoint(cp);continue;}result.append(',');}
        String clean=result.toString().replaceAll(",+",",").strip();return clean.isBlank()?".":clean;
    }
    private void requestSingle(Path wave,String text,int sid,double rate,Path directory)throws Exception{start(directory,rate);Files.deleteIfExists(wave);String encoded=Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));input.write(wave+"\t"+sid+"\t"+encoded);input.newLine();input.flush();long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(12);while(!output.ready()){if(process==null||!process.isAlive())throw new IllegalStateException("worker 已停止"+diagnosticSuffix());if(System.nanoTime()>=deadline)throw new IllegalStateException("worker 超过 12 分钟未返回");Thread.sleep(250);}String reply=output.readLine();if(reply==null||!reply.startsWith("OK"))throw new IllegalStateException((reply==null?"worker 已停止":reply)+diagnosticSuffix());if(!Files.isRegularFile(wave)||Files.size(wave)<=46)throw new IllegalStateException("worker 未生成有效 WAV"+diagnosticSuffix());}
    private void start(Path directory,double rate)throws Exception{if(process!=null&&process.isAlive()&&Double.compare(workerRate,rate)==0)return;if(process!=null)restartWorker();synchronized(stderrTail){stderrTail.clear();}ProcessBuilder builder=new ProcessBuilder(locatePython().toString(),"-u",locateWorker().toString(),"--model",model==Model.KOKORO?"kokoro":"aishell3","--model-dir",directory.toString(),"--rate",Double.toString(rate));builder.environment().put("PYTHONPATH",locatePythonPackage().toString());builder.environment().put("PYTHONUTF8","1");if(workerThreads>0)builder.environment().put("SHERPA_TTS_THREADS",Integer.toString(workerThreads));process=builder.start();workerRate=rate;output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));BufferedReader errors=new BufferedReader(new InputStreamReader(process.getErrorStream(),StandardCharsets.UTF_8));Thread drainer=new Thread(()->drainErrors(errors),"x3-sherpa-diagnostics");drainer.setDaemon(true);drainer.start();long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);while(!output.ready()){if(!process.isAlive())throw new IllegalStateException("worker 启动失败"+diagnosticSuffix());if(System.nanoTime()>deadline){restartWorker();throw new IllegalStateException("worker 模型加载超过 2 分钟");}Thread.sleep(100);}String ready=output.readLine();if(!"READY".equals(ready)){restartWorker();throw new IllegalStateException("worker 启动失败"+diagnosticSuffix());}}
    private void drainErrors(BufferedReader errors){try{String line;while((line=errors.readLine())!=null){String clean=line.replaceAll("[\\p{Cntrl}&&[^\\t]]"," ");if(clean.length()>400)clean=clean.substring(0,400);synchronized(stderrTail){stderrTail.addLast(clean);while(stderrTail.size()>12)stderrTail.removeFirst();}}}catch(Exception ignored){}}
    private String diagnosticSuffix(){synchronized(stderrTail){if(stderrTail.isEmpty())return "";StringBuilder text=new StringBuilder("；诊断：");int skip=Math.max(0,stderrTail.size()-5),index=0;for(String line:stderrTail){if(index++<skip)continue;if(!line.isBlank())text.append(' ').append(line);}return text.toString();}}
    private void restartWorker(){close();}
    private Path locatePython() throws Exception { Path path=root.resolve(".tools/melo/python.exe");if(Files.isRegularFile(path))return path;throw new IllegalStateException("便携 Python 未安装"); }
    private Path locateWorker() throws Exception { Path path=root.resolve("builder/sherpa/worker.py");if(Files.isRegularFile(path))return path;throw new IllegalStateException("sherpa worker 缺失"); }
    private Path locatePythonPackage() throws Exception { Path path=root.resolve(".tools/sherpa-onnx/python/site-packages");if(Files.isDirectory(path.resolve("sherpa_onnx")))return path;throw new IllegalStateException("sherpa-onnx Python Runtime 未安装"); }
    private Path locateModelDirectory() throws Exception {
        Path models = root.resolve(".tools/sherpa-onnx/models"); String marker = model == Model.KOKORO ? "kokoro" : "aishell3";
        if (Files.isDirectory(models)) try (Stream<Path> paths = Files.list(models)) { Path found = paths.filter(Files::isDirectory).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(marker)).findFirst().orElse(null); if (found != null) return found; }
        throw new IllegalStateException("未安装离线模型：" + marker);
    }
    private static Path findOnnx(Path directory) throws Exception { try (Stream<Path> paths = Files.walk(directory)) { List<Path> models=paths.filter(p->!cachedMetadata(p)).filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".onnx")).toList();return models.stream().filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).contains("int8")).findFirst().orElseGet(()->models.stream().findFirst().orElseThrow(()->new IllegalStateException("模型 ONNX 文件缺失"))); } }
    private static Path find(Path directory, String name) throws Exception { if (!Files.isDirectory(directory)) throw new IllegalStateException("目录不存在：" + directory.getFileName()); try (Stream<Path> paths = Files.walk(directory)) { return paths.filter(p->!cachedMetadata(p)).filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new IllegalStateException("模型文件缺失：" + name)); } }
    private static boolean cachedMetadata(Path path){for(Path part:path)if(part.toString().equalsIgnoreCase(".cache"))return true;return false;}
    @Override public synchronized void close(){try{if(input!=null&&process!=null&&process.isAlive()){input.write("QUIT");input.newLine();input.flush();}}catch(Exception ignored){}finally{if(process!=null){try{if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();process.destroyForcibly();}process=null;input=null;output=null;workerRate=Double.NaN;}}}
}
