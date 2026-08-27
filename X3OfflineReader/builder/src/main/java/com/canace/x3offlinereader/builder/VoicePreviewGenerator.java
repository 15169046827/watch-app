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
    private VoicePreviewGenerator() {}

    public static void main(String[] args) throws Exception {
        Path root=BuilderMain.findProjectRoot();String only=null;boolean overwrite=false,indexOnly=false;int shardCount=1,shardIndex=0;
        for(int i=0;i<args.length;i++){
            if("--provider".equals(args[i]))only=args[++i];
            else if("--overwrite".equals(args[i]))overwrite=true;
            else if("--index-only".equals(args[i]))indexOnly=true;
            else if("--shard-count".equals(args[i]))shardCount=Integer.parseInt(args[++i]);
            else if("--shard-index".equals(args[i]))shardIndex=Integer.parseInt(args[++i]);
            else throw new IllegalArgumentException("Unknown option: "+args[i]);
        }
        if(indexOnly){writeIndex(root);System.out.println("Voice preview index updated.");return;}
        if(shardCount<1||shardIndex<0||shardIndex>=shardCount)throw new IllegalArgumentException("Invalid shard configuration");
        List<String> failures=new ArrayList<>();int generated=0,skipped=0,total=0;
        for(TtsProviderCatalog.Entry entry:TtsProviderCatalog.guiEntries()){
            if(only!=null&&!entry.id().equalsIgnoreCase(only))continue;
            TtsProvider provider=TtsProviderCatalog.previewProvider(entry.id(),root,null);
            try{
                if(!provider.isAvailable()){failures.add(entry.id()+": provider unavailable");continue;}
                List<String> voices=provider.voices();total+=voices.size();int index=0;
                for(String voice:voices){
                    index++;if((index-1)%shardCount!=shardIndex)continue;Path target=VoicePreviewLibrary.path(root,entry.id(),voice);
                    if(!overwrite&&VoicePreviewLibrary.available(root,entry.id(),voice)){skipped++;continue;}
                    try{
                        Path work=root.resolve("builder/.work/preview-generation").resolve(entry.id());Files.createDirectories(work);
                        String id="preview-"+VoicePreviewLibrary.key(entry.id(),voice);
                        String sentence=VoicePreviewLibrary.sentence(entry.id());
                        if(!overwrite&&Files.isRegularFile(target))sentence=VoicePreviewLibrary.adjustedSentence(entry.id(),WaveSupport.durationMs(target));
                        SynthesisResult raw=provider.synthesize(new SynthesisRequest(id,sentence,voice,VoicePreviewLibrary.RATE),work);
                        normalize(root,raw.getWavPath(),target);
                        long duration=WaveSupport.durationMs(target);
                        String adjusted=VoicePreviewLibrary.adjustedSentence(entry.id(),duration);
                        if(!adjusted.equals(sentence)){
                            raw=provider.synthesize(new SynthesisRequest(id+"-adjusted",adjusted,voice,VoicePreviewLibrary.RATE),work);
                            normalize(root,raw.getWavPath(),target);duration=WaveSupport.durationMs(target);
                        }
                        if(duration<5000||duration>10000)throw new IllegalStateException("natural duration "+duration+" ms; adjust preview text instead of audio speed");
                        generated++;System.out.printf(Locale.ROOT,"[%s %d/%d] %s (%d ms)%n",entry.id(),index,voices.size(),voice,duration);
                    }catch(Exception error){failures.add(entry.id()+" / "+voice+": "+error.getClass().getSimpleName()+" "+safe(error));}
                }
            }finally{if(provider instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}}
        }
        if(shardCount==1)writeIndex(root);
        System.out.printf(Locale.ROOT,"Preview generation complete: total=%d generated=%d cached=%d failures=%d%n",total,generated,skipped,failures.size());
        if(!failures.isEmpty())throw new IllegalStateException("Preview failures:\n"+String.join("\n",failures));
    }

    private static void normalize(Path root,Path source,Path target)throws Exception{
        Files.createDirectories(target.getParent());Path staging=target.resolveSibling(target.getFileName()+".tmp.wav");Files.deleteIfExists(staging);
        List<String> command=new ArrayList<>();command.add(ffmpeg(root).toString());command.addAll(List.of("-hide_banner","-loglevel","error","-y","-i",source.toString()));
        command.addAll(List.of("-ar","16000","-ac","1","-c:a","pcm_s16le",staging.toString()));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();String diagnostic=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(process.waitFor()!=0)throw new IllegalStateException("FFmpeg preview normalization failed: "+diagnostic.strip());
        Files.move(staging,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        if(!source.equals(target))Files.deleteIfExists(source);
    }

    private static Path ffmpeg(Path root)throws Exception{
        try(var paths=Files.walk(root.resolve(".tools/ffmpeg"))){return paths.filter(Files::isRegularFile).filter(path->path.getFileName().toString().equalsIgnoreCase("ffmpeg.exe")).findFirst().orElseThrow();}
    }

    private static void writeIndex(Path root)throws Exception{
        List<String> lines=new ArrayList<>();lines.add("revision\tprovider\tvoice\tfile\tdurationMs\tsentence");
        for(TtsProviderCatalog.Entry entry:TtsProviderCatalog.guiEntries()){
            TtsProvider provider=TtsProviderCatalog.previewProvider(entry.id(),root,null);
            try{for(String voice:provider.voices()){Path path=VoicePreviewLibrary.path(root,entry.id(),voice);if(VoicePreviewLibrary.available(root,entry.id(),voice))lines.add(String.join("\t",VoicePreviewLibrary.REVISION,entry.id(),voice,path.getFileName().toString(),Long.toString(WaveSupport.durationMs(path)),VoicePreviewLibrary.sentence(entry.id())));}}
            catch(Exception ignored){}finally{if(provider instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}}
        }
        Path index=root.resolve("builder/voice-previews/index.tsv");Files.createDirectories(index.getParent());Files.write(index,lines,StandardCharsets.UTF_8);
    }
    private static String safe(Exception error){String message=error.getMessage();return message==null?"":message.replace('\n',' ').replace('\r',' ');}
}
