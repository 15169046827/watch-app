package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.canace.x3offlinereader.builder.tts.ToneTtsProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AudiobookBuilderPauseTest {
    @TempDir Path temporary;

    @Test void cooperativePauseLeavesAtomicSentenceCacheForResume()throws Exception{
        Path source=temporary.resolve("book.txt");StringBuilder text=new StringBuilder();for(int i=0;i<120;i++)text.append("这是公开断点回归句子").append(i).append("。\n");Files.writeString(source,text);
        Path ffmpeg=temporary.resolve(".tools/ffmpeg/ffmpeg.exe");Files.createDirectories(ffmpeg.getParent());Files.write(ffmpeg,new byte[]{0});
        TtsProvider slow=new TtsProvider(){final ToneTtsProvider tone=new ToneTtsProvider();@Override public String id(){return "pause-test-v1";}@Override public boolean isAvailable(){return true;}@Override public int parallelism(){return 1;}@Override public SynthesisResult synthesize(SynthesisRequest request,Path workingDirectory)throws Exception{Thread.sleep(20);return tone.synthesize(request,workingDirectory);}};
        BuilderOptions options=new BuilderOptions();options.inputs.add(source);options.output=temporary.resolve("paused.opbook");options.tts="tone";options.voice="tone";BuildControl control=new BuildControl();
        var executor=Executors.newSingleThreadExecutor();var future=executor.submit(()->new AudiobookBuilder(temporary,(ignored,root)->slow).build(options,BuildProgress.NONE,control));
        Path cache=temporary.resolve("builder/.work/cache");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!hasFile(cache)&&System.nanoTime()<deadline)Thread.sleep(20);
        control.requestStop();ExecutionException failure=org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));Throwable cause=failure.getCause();while(cause.getCause()!=null)cause=cause.getCause();assertInstanceOf(BuildControl.PauseRequestedException.class,cause);
        try(var files=Files.list(cache)){assertTrue(files.allMatch(path->path.getFileName().toString().endsWith(".wav")),"no partial cache files remain");}
        executor.shutdownNow();
    }

    private static boolean hasFile(Path directory)throws Exception{if(!Files.isDirectory(directory))return false;try(var files=Files.list(directory)){return files.findAny().isPresent();}}
}
