package com.canace.x3offlinereader.builder;

import com.canace.x3offlinereader.builder.audio.FfmpegAudioEncoder;
import com.canace.x3offlinereader.builder.audio.WaveSupport;
import com.canace.x3offlinereader.builder.format.OpbookWriter;
import com.canace.x3offlinereader.builder.model.BookModel;
import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.source.EpubSourceParser;
import com.canace.x3offlinereader.builder.source.TextSourceParser;
import com.canace.x3offlinereader.builder.spi.SourceParser;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.canace.x3offlinereader.builder.text.SentenceSegmenter;
import com.canace.x3offlinereader.builder.util.Ids;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AudiobookBuilder {
    @FunctionalInterface interface ProviderFactory { TtsProvider create(BuilderOptions options,Path root)throws Exception; }
    private static final class ChapterWork {
        final BookModel.Chapter chapter;
        final List<SentenceSegmenter.Segment> segments;
        final List<CompletableFuture<SynthesisResult>> results=new ArrayList<>();
        final Path sentenceDir;
        ChapterWork(BookModel.Chapter chapter,List<SentenceSegmenter.Segment> segments,Path sentenceDir){this.chapter=chapter;this.segments=segments;this.sentenceDir=sentenceDir;}
    }

    private final Path projectRoot;
    private final ProviderFactory providerFactory;
    public AudiobookBuilder(Path projectRoot){this(projectRoot,TtsProviderCatalog::buildProvider);}
    AudiobookBuilder(Path projectRoot,ProviderFactory providerFactory){this.projectRoot=projectRoot.toAbsolutePath().normalize();this.providerFactory=providerFactory;}
    public Path build(BuilderOptions options)throws Exception{return build(options,BuildProgress.NONE,new BuildControl());}
    public Path build(BuilderOptions options,BuildProgress progress)throws Exception{return build(options,progress,new BuildControl());}

    public Path build(BuilderOptions options,BuildProgress suppliedProgress,BuildControl suppliedControl)throws Exception{
        final BuildProgress progress=suppliedProgress==null?BuildProgress.NONE:suppliedProgress;
        final BuildControl control=suppliedControl==null?new BuildControl():suppliedControl;
        control.checkpoint();progress.update(0,"检查输入与输出");validate(options);
        progress.update(3,"解析 EPUB/TXT 正文与章节");BookModel.Book book=parse(options);control.checkpoint();
        SentenceSegmenter segmenter=new SentenceSegmenter();int chapterCount=0,totalSegments=0;long totalCharacters=0;
        for(BookModel.Volume volume:book.volumes)for(BookModel.Chapter chapter:volume.chapters){chapterCount++;List<SentenceSegmenter.Segment> segments=segmenter.segment(chapter);totalSegments+=segments.size();for(SentenceSegmenter.Segment segment:segments)totalCharacters+=codePoints(segment.text);}
        progress.update(7,"解析完成："+book.volumes.size()+" 卷 / "+chapterCount+" 章 / "+totalSegments+" 句");
        if(TtsProviderCatalog.entry(options.tts).online()&&!options.allowOnlineTts)throw new IllegalArgumentException("在线语音需要明确授权；GUI 会在转换前询问，CLI 请添加 --allow-online-tts");
        TtsProvider tts=providerFactory.create(options,projectRoot);if(!tts.isAvailable())throw new IllegalStateException("TTS provider unavailable: "+options.tts);
        ExecutorService synthesisPool=Executors.newFixedThreadPool(tts.parallelism(),task->{Thread thread=new Thread(task,"x3-tts-synthesis");thread.setDaemon(true);return thread;});
        Path work=projectRoot.resolve("builder/.work/"+book.bookId);Files.createDirectories(work);
        try{
            int initiallyCached=0;long cachedCharacters=0,uncachedCharacters=0;
            for(BookModel.Volume volume:book.volumes)for(BookModel.Chapter chapter:volume.chapters)for(SentenceSegmenter.Segment segment:segmenter.segment(chapter)){
                Path cache=cachePath(tts,options,segment);if(validWave(cache)){initiallyCached++;cachedCharacters+=codePoints(segment.text);}else uncachedCharacters+=codePoints(segment.text);
            }
            final int reusedSegments=initiallyCached;
            long initialEstimate=estimateSeconds(options.tts,uncachedCharacters,reusedSegments,chapterCount,options.rate,tts.parallelism());progress.estimateSeconds(initialEstimate);
            progress.update(8,"按文本长度与缓存初步估算：约 "+humanDuration(initialEstimate)+"（未缓存 "+uncachedCharacters+" 字；运行中按实际吞吐校准）");
            progress.update(9,options.tts.equalsIgnoreCase("melo")?"准备 MeloTTS（"+tts.parallelism()+" 个工作进程；首次加载模型可能需要一些时间）":"准备语音引擎："+TtsProviderCatalog.entry(options.tts).label());
            FfmpegAudioEncoder encoder=new FfmpegAudioEncoder(options.ffmpeg,projectRoot);List<OpbookWriter.TrackArtifact> tracks=new ArrayList<>();
            // Progress describes work performed by this run. Cached sentences reduce the
            // remaining estimate, but must not make a fresh run appear to jump to 40%+.
            AtomicInteger generatedSegments=new AtomicInteger();AtomicLong generatedCharacters=new AtomicLong();progress.update(10,"语音流水线已启动：已完成句子立即落入断点缓存，已编码 Track 可直接复用");

            List<ChapterWork> chapterWorks=new ArrayList<>();Map<Path,CompletableFuture<SynthesisResult>> pending=new LinkedHashMap<>();Set<Path> initialCache=new HashSet<>();
            for(BookModel.Volume volume:book.volumes)for(BookModel.Chapter chapter:volume.chapters){
                List<SentenceSegmenter.Segment> segments=segmenter.segment(chapter);if(segments.isEmpty())throw new IllegalArgumentException("Chapter has no speakable text: "+chapter.title);
                Path sentenceDir=work.resolve("sentences/"+chapter.chapterId);Files.createDirectories(sentenceDir);ChapterWork chapterWork=new ChapterWork(chapter,segments,sentenceDir);
                for(SentenceSegmenter.Segment segment:segments){
                    Path cache=cachePath(tts,options,segment);Files.createDirectories(cache.getParent());CompletableFuture<SynthesisResult> future;
                    if(validWave(cache)){initialCache.add(cache);future=CompletableFuture.completedFuture(new SynthesisResult(cache,WaveSupport.durationMs(cache)));}
                    else{
                        CompletableFuture<SynthesisResult> base=pending.get(cache);
                        if(base==null){SynthesisRequest request=new SynthesisRequest(segment.segmentId,segment.text,options.voice,options.rate);base=CompletableFuture.supplyAsync(()->{try{return synthesizeToCache(tts,request,sentenceDir,cache,control);}catch(Exception failure){throw new CompletionException(failure);}},synthesisPool);pending.put(cache,base);}
                        int characters=codePoints(segment.text);long allCharacters=uncachedCharacters;int allSegments=totalSegments-initiallyCached;
                        future=base.thenApply(result->{int done=generatedSegments.incrementAndGet();long doneCharacters=generatedCharacters.addAndGet(characters);progress.update(synthesisPercent(doneCharacters,allCharacters),done==allSegments?"语音生成完成：本次 "+done+" 句，复用缓存 "+reusedSegments+" 句":null);return result;});
                    }
                    chapterWork.results.add(future);
                }
                chapterWorks.add(chapterWork);
            }
            if(uncachedCharacters==0)progress.update(78,"命中完整语音缓存：已复用 "+reusedSegments+" 句，跳过语音生成");

            int cachedSegments=0,chapterIndex=0;
            for(ChapterWork chapterWork:chapterWorks){
                control.checkpoint();BookModel.Chapter chapter=chapterWork.chapter;chapterIndex++;
                progress.update(Math.max(encodePercent(chapterIndex-1,chapterCount),Math.max(10,synthesisPercent(generatedCharacters.get(),uncachedCharacters))),"章节 "+chapterIndex+"/"+chapterCount+"：等待语音并准备 Track");
                List<Path> waves=new ArrayList<>();List<Long> durations=new ArrayList<>();
                for(int segmentIndex=0;segmentIndex<chapterWork.segments.size();segmentIndex++){
                    control.checkpoint();SynthesisResult result=await(chapterWork.results.get(segmentIndex),control);Path cache=result.getWavPath();if(initialCache.contains(cache))cachedSegments++;
                    waves.add(cache);durations.add(result.getDurationMs());
                }
                progress.update(encodePercent(chapterIndex-1,chapterCount),"章节 "+chapterIndex+"/"+chapterCount+"：拼接/复用并编码 M4A（缓存命中 "+cachedSegments+" 句）");
                long maxTrackMs=options.maxTrackMinutes*60_000L;int start=0,part=0;
                while(start<chapterWork.segments.size()){
                    control.checkpoint();int end=start;long expectedDuration=0;
                    while(end<chapterWork.segments.size()){long added=durations.get(end)+(end>start?options.pauseMs:0);if(end>start&&expectedDuration+added>maxTrackMs)break;expectedDuration+=added;end++;}
                    String baseId=chapter.chapterId.replace("chapter","track");String trackId=part==0?baseId:baseId+"-p"+String.format("%03d",part+1);
                    Path m4a=work.resolve(trackId+".m4a"),metadata=work.resolve(trackId+".track.properties");String signature=trackSignature(tts,options,trackId,waves.subList(start,end),durations.subList(start,end));long combinedDuration=expectedDuration;
                    if(validTrack(m4a,metadata,signature)){progress.update(encodePercent(chapterIndex,chapterCount),"章节 "+chapterIndex+"/"+chapterCount+"：复用已完成 Track "+trackId);}
                    else{
                        Path combined=work.resolve(trackId+".wav");try{combinedDuration=WaveSupport.concatenate(new ArrayList<>(waves.subList(start,end)),options.pauseMs,combined);encoder.encodeWave(combined,m4a,control);writeTrackMetadata(metadata,signature,combinedDuration);}finally{Files.deleteIfExists(combined);}
                    }
                    OpbookWriter.TrackArtifact artifact=new OpbookWriter.TrackArtifact();artifact.trackId=trackId;artifact.chapter=chapter;artifact.m4a=m4a;artifact.durationMs=combinedDuration;long cursor=0;
                    for(int i=start;i<end;i++){OpbookWriter.TimedSegment timed=new OpbookWriter.TimedSegment();timed.segment=chapterWork.segments.get(i);timed.startMs=cursor;timed.endMs=i+1==end?combinedDuration:cursor+durations.get(i);artifact.segments.add(timed);cursor=timed.endMs+(i+1<end?options.pauseMs:0);}
                    tracks.add(artifact);start=end;part++;
                }
                progress.update(encodePercent(chapterIndex,chapterCount),null);
            }
            control.checkpoint();progress.update(91,"生成并编码封面");Path cover=work.resolve("cover.webp");encoder.encodeCover(options.cover,cover,control);
            control.checkpoint();progress.update(95,"写入 manifest、正文、Timeline 与校验和");Exception packageFailure=null;
            for(int attempt=1;attempt<=2;attempt++){control.checkpoint();try{new OpbookWriter().write(book,tracks,cover,options.output.toAbsolutePath(),work.resolve("staging"),options.createdAt,encoder.version());packageFailure=null;break;}catch(Exception failure){packageFailure=failure;if(attempt==1)progress.update(95,"打包步骤失败，保留断点与语音缓存并自动重试一次");}}
            if(packageFailure!=null)throw packageFailure;
            new BuilderCacheManager(projectRoot).afterSuccessfulBuild(work,progress);progress.update(100,"转换完成");return options.output.toAbsolutePath();
        }finally{
            synthesisPool.shutdownNow();try{synthesisPool.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            if(tts instanceof AutoCloseable)((AutoCloseable)tts).close();
        }
    }

    private SynthesisResult synthesizeToCache(TtsProvider tts,SynthesisRequest request,Path sentenceDir,Path cache,BuildControl control)throws Exception{
        control.checkpoint();SynthesisResult generated=tts.synthesize(request,sentenceDir);Path temporary=cache.resolveSibling(cache.getFileName()+".tmp-"+UUID.randomUUID());
        try{Files.copy(generated.getWavPath(),temporary,StandardCopyOption.REPLACE_EXISTING);moveAtomic(temporary,cache);return new SynthesisResult(cache,generated.getDurationMs());}
        finally{Files.deleteIfExists(temporary);if(!generated.getWavPath().toAbsolutePath().normalize().equals(cache.toAbsolutePath().normalize()))Files.deleteIfExists(generated.getWavPath());}
    }

    private Path cachePath(TtsProvider tts,BuilderOptions options,SentenceSegmenter.Segment segment){String cacheId=Ids.sha256(tts.id()+"\n"+options.voice+"\n"+options.rate+"\n"+segment.text).substring(0,24);return projectRoot.resolve("builder/.work/cache/"+cacheId+".wav");}
    private static String trackSignature(TtsProvider tts,BuilderOptions options,String trackId,List<Path> waves,List<Long> durations){StringBuilder value=new StringBuilder(tts.id()).append('|').append(options.voice).append('|').append(options.rate).append('|').append(options.pauseMs).append('|').append(trackId);for(int i=0;i<waves.size();i++)value.append('|').append(waves.get(i).getFileName()).append(':').append(durations.get(i));return Ids.sha256(value.toString());}
    private static boolean validTrack(Path m4a,Path metadata,String signature){try{if(!Files.isRegularFile(m4a)||Files.size(m4a)<512||!Files.isRegularFile(metadata))return false;Properties values=new Properties();try(InputStream input=Files.newInputStream(metadata)){values.load(input);}return signature.equals(values.getProperty("signature"));}catch(Exception ignored){return false;}}
    private static void writeTrackMetadata(Path metadata,String signature,long duration)throws Exception{Properties values=new Properties();values.setProperty("signature",signature);values.setProperty("durationMs",Long.toString(duration));Path temporary=metadata.resolveSibling(metadata.getFileName()+".tmp-"+UUID.randomUUID());try(OutputStream output=Files.newOutputStream(temporary)){values.store(output,"Reusable encoded Track");}try{moveAtomic(temporary,metadata);}finally{Files.deleteIfExists(temporary);}}
    private static void moveAtomic(Path source,Path target)throws Exception{try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}}
    private static boolean validWave(Path path){try{return Files.isRegularFile(path)&&Files.size(path)>46;}catch(Exception ignored){return false;}}
    private static int codePoints(String text){return text.codePointCount(0,text.length());}
    static int synthesisPercent(long completed,long total){return total<=0?78:Math.min(78,10+(int)Math.round(completed*68.0/total));}
    private static int encodePercent(int completedChapters,int totalChapters){return totalChapters<=0?90:Math.min(90,79+(int)Math.round(completedChapters*11.0/totalChapters));}
    static long estimateSeconds(String provider,long uncachedCharacters,int cachedSegments,int chapters,double rate){return estimateSeconds(provider,uncachedCharacters,cachedSegments,chapters,rate,1);}
    static long estimateSeconds(String provider,long uncachedCharacters,int cachedSegments,int chapters,double rate,int parallelism){double efficiency="melo".equalsIgnoreCase(provider)&&parallelism>1?1.25:1.0;double base=switch(provider.toLowerCase()){case "melo"->7.5;case "windows"->13.0;case "sherpa-kokoro"->4.0;case "sherpa-aishell3"->3.0;case "edge"->10.0;default->1000.0;};double speed=base*efficiency;long startup=uncachedCharacters==0?0:"melo".equalsIgnoreCase(provider)?parallelism>1?38:25:provider.startsWith("sherpa-")?20:"windows".equalsIgnoreCase(provider)?3:2;long synthesis=(long)Math.ceil(uncachedCharacters/(speed*Math.max(0.5,rate)));long cacheScan=Math.max(1,(cachedSegments+249)/250);long encode=Math.max(8,chapters*3L);return Math.max(10,startup+synthesis+cacheScan+encode+5);}
    private static SynthesisResult await(CompletableFuture<SynthesisResult> future,BuildControl control)throws Exception{while(true){control.checkpoint();try{return future.get(250,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(ExecutionException failure){Throwable cause=failure.getCause();while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof Exception exception)throw exception;throw new IllegalStateException(cause);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();control.requestStop();control.checkpoint();}}}
    private static String humanDuration(long seconds){long hours=seconds/3600,minutes=(seconds%3600)/60,remaining=seconds%60;return hours>0?String.format("%d 小时 %02d 分 %02d 秒",hours,minutes,remaining):String.format("%02d 分 %02d 秒",minutes,remaining);}

    private static BookModel.Book parse(BuilderOptions options)throws Exception{
        List<SourceParser> parsers=List.of(new TextSourceParser(),new EpubSourceParser());BookModel.Book book=new BookModel.Book();book.title=options.title!=null?options.title:stripExtension(options.inputs.get(0).getFileName().toString());book.authors.add(effectiveAuthor(options.author));book.language=options.language;StringBuilder fingerprint=new StringBuilder(book.title);
        for(int i=0;i<options.inputs.size();i++){Path source=options.inputs.get(i).toAbsolutePath();SourceParser parser=parsers.stream().filter(candidate->candidate.supports(source)).findFirst().orElseThrow(()->new IllegalArgumentException("Unsupported source: "+source));BookModel.Volume volume=parser.parse(source,i);book.volumes.add(volume);fingerprint.append('|').append(Ids.sha256(source));}
        book.bookId=Ids.bookId(book.title,fingerprint.toString());return book;
    }

    private static void validate(BuilderOptions options){if(options.inputs.isEmpty())throw new IllegalArgumentException("At least one --input is required");if(options.output==null)throw new IllegalArgumentException("--output is required");if(options.pauseMs<0||options.pauseMs>10000)throw new IllegalArgumentException("--pause-ms must be 0..10000");if(options.maxTrackMinutes<1||options.maxTrackMinutes>120)throw new IllegalArgumentException("--max-track-minutes must be 1..120");if(options.meloWorkers<1||options.meloWorkers>2)throw new IllegalArgumentException("--melo-workers must be 1..2");for(Path path:options.inputs)if(!Files.isRegularFile(path))throw new IllegalArgumentException("Input not found: "+path);if(options.cover!=null&&!Files.isRegularFile(options.cover))throw new IllegalArgumentException("Cover not found: "+options.cover);}
    static String effectiveAuthor(String author){return author==null||author.isBlank()?"未署名":author.trim();}
    private static String stripExtension(String name){int dot=name.lastIndexOf('.');return dot>0?name.substring(0,dot):name;}
}
