package com.canace.x3offlinereader.builder.audio;

import com.canace.x3offlinereader.builder.BuildControl;
import com.canace.x3offlinereader.builder.spi.AudioEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FfmpegAudioEncoder implements AudioEncoder {
    private final Path executable;

    public FfmpegAudioEncoder(Path configured, Path projectRoot) throws Exception {
        this.executable = find(configured, projectRoot);
    }

    @Override public void encodeM4a(List<Path> sentenceWavs, Path outputM4a) throws Exception {
        throw new UnsupportedOperationException("Use encodeWave after PCM concatenation");
    }

    public void encodeWave(Path wave, Path outputM4a) throws Exception {
        encodeWave(wave,outputM4a,null);
    }

    public void encodeWave(Path wave, Path outputM4a, BuildControl control) throws Exception {
        Files.createDirectories(outputM4a.getParent());
        run(List.of(executable.toString(), "-hide_banner", "-loglevel", "error", "-y","-i", wave.toString(), "-vn", "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "64k","-movflags", "+faststart", outputM4a.toString()),outputM4a,control);
    }

    public void decodeToWave(Path input, Path outputWave) throws Exception {
        Files.createDirectories(outputWave.getParent());
        run(List.of(executable.toString(), "-hide_banner", "-loglevel", "error", "-y", "-i", input.toString(), "-vn", "-acodec", "pcm_s16le", outputWave.toString()), outputWave,null);
    }

    public String version() throws Exception {
        Process process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("FFmpeg version check failed");
        return text.lines().findFirst().orElse("ffmpeg");
    }

    public void encodeCover(Path input,Path output)throws Exception{encodeCover(input,output,null);}
    public void encodeCover(Path input,Path output,BuildControl control)throws Exception{Files.createDirectories(output.getParent());if(input==null)run(List.of(executable.toString(),"-hide_banner","-loglevel","error","-y","-f","lavfi","-i","color=c=0x14202b:s=512x512","-frames:v","1","-c:v","libwebp","-lossless","1",output.toString()),output,control);else run(List.of(executable.toString(),"-hide_banner","-loglevel","error","-y","-i",input.toString(),"-vf","scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x14202b","-frames:v","1","-c:v","libwebp","-q:v","82",output.toString()),output,control);}
    private static void run(List<String> command,Path output,BuildControl control)throws Exception{String last="";for(int attempt=1;attempt<=2;attempt++){Files.deleteIfExists(output);Process process=new ProcessBuilder(command).redirectErrorStream(true).start();CompletableFuture<byte[]> reader=CompletableFuture.supplyAsync(()->{try{return process.getInputStream().readAllBytes();}catch(Exception ignored){return new byte[0];}});long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(12);boolean ended=false;while(!(ended=process.waitFor(250,TimeUnit.MILLISECONDS))){if(control!=null&&control.isStopRequested()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);control.checkpoint();}if(System.nanoTime()>=deadline)break;}if(!ended){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);last="超过 12 分钟无响应";}else{last=new String(reader.get(10,TimeUnit.SECONDS),StandardCharsets.UTF_8).strip();if(process.exitValue()==0&&Files.isRegularFile(output)&&Files.size(output)>0)return;}if(attempt==1)continue;}throw new IllegalStateException("FFmpeg 当前步骤自动重试一次后仍失败: "+last);}

    private static Path find(Path configured, Path projectRoot) throws Exception {
        if (configured != null && Files.isRegularFile(configured)) return configured.toAbsolutePath();
        Path tools = projectRoot.resolve(".tools/ffmpeg");
        if (Files.isDirectory(tools)) try (Stream<Path> paths = Files.walk(tools)) {
            Path found = paths.filter(path -> path.getFileName().toString().equalsIgnoreCase("ffmpeg.exe")).findFirst().orElse(null);
            if (found != null) return found.toAbsolutePath();
        }
        Process where = new ProcessBuilder("where.exe", "ffmpeg.exe").redirectErrorStream(true).start();
        String result = new String(where.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (where.waitFor() == 0 && !result.isBlank()) return Path.of(result.lines().findFirst().orElseThrow());
        throw new IllegalStateException("FFmpeg not found; use --ffmpeg <path>");
    }
}
