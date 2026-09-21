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

    @FunctionalInterface
    interface ProviderFactory {

        TtsProvider create(BuilderOptions options, Path root) throws Exception;
    }

    private static final class ChapterWork {

        final BookModel.Chapter chapter;

        final List<SentenceSegmenter.Segment> segments;

        final List<CompletableFuture<SynthesisResult>> results = new ArrayList<>();

        final Path sentenceDir;

        ChapterWork(
                BookModel.Chapter chapter,
                List<SentenceSegmenter.Segment> segments,
                Path sentenceDir) {
            this.chapter = chapter;
            this.segments = segments;
            this.sentenceDir = sentenceDir;
        }
    }

    private final Path projectRoot;

    private final ProviderFactory providerFactory;

    public AudiobookBuilder(Path projectRoot) {
        this(projectRoot, TtsProviderCatalog::buildProvider);
    }

    AudiobookBuilder(Path projectRoot, ProviderFactory providerFactory) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.providerFactory = providerFactory;
    }

    public Path build(BuilderOptions options) throws Exception {
        return build(options, BuildProgress.NONE, new BuildControl());
    }

    public Path build(BuilderOptions options, BuildProgress progress) throws Exception {
        return build(options, progress, new BuildControl());
    }

    public Path build(
            BuilderOptions options, BuildProgress suppliedProgress, BuildControl suppliedControl)
            throws Exception {
        BuildProgress progress = suppliedProgress == null ? BuildProgress.NONE : suppliedProgress;
        BuildControl control = suppliedControl == null ? new BuildControl() : suppliedControl;
        control.checkpoint();
        progress.update(0, "检查输入与输出");
        validate(options);
        progress.update(3, "解析 EPUB/TXT 正文与章节");
        BookModel.Book book = parse(options);
        control.checkpoint();
        SentenceSegmenter segmenter = new SentenceSegmenter();
        BookAnalysis analysis = analyzeBook(book, segmenter);
        reportParsed(progress, book, analysis);
        requireOnlineAuthorization(options);
        TtsProvider tts = providerFactory.create(options, projectRoot);
        requireProviderAvailable(tts, options.tts);
        ExecutorService synthesisPool = synthesisPool(tts.parallelism());
        try {
            Path work = BuilderWorkDirectory.resolve(projectRoot).resolve(book.bookId);
            Files.createDirectories(work);
            return buildPipeline(
                    options,
                    progress,
                    control,
                    book,
                    segmenter,
                    analysis,
                    tts,
                    synthesisPool,
                    work);
        } finally {
            closeResources(synthesisPool, tts);
        }
    }

    private static BookAnalysis analyzeBook(
            BookModel.Book book, SentenceSegmenter segmenter) {
        BookAnalysis result = new BookAnalysis();
        for (BookModel.Volume volume : book.volumes) {
            for (BookModel.Chapter chapter : volume.chapters) {
                result.chapterCount++;
                List<SentenceSegmenter.Segment> segments = segmenter.segment(chapter);
                result.totalSegments += segments.size();
                for (SentenceSegmenter.Segment segment : segments) {
                    result.totalCharacters += codePoints(segment.text);
                }
            }
        }
        return result;
    }

    private static void reportParsed(
            BuildProgress progress, BookModel.Book book, BookAnalysis analysis) {
        progress.update(
                7,
                "解析完成："
                        + book.volumes.size()
                        + " 卷 / "
                        + analysis.chapterCount
                        + " 章 / "
                        + analysis.totalSegments
                        + " 句");
    }

    private static void requireOnlineAuthorization(BuilderOptions options) {
        if (TtsProviderCatalog.entry(options.tts).online() && !options.allowOnlineTts) {
            throw new IllegalArgumentException(
                    "在线语音需要明确授权；GUI 会在转换前询问，CLI 请添加 --allow-online-tts");
        }
    }

    private static void requireProviderAvailable(TtsProvider provider, String providerId) {
        if (!provider.isAvailable()) {
            throw new IllegalStateException("TTS provider unavailable: " + providerId);
        }
    }

    private static ExecutorService synthesisPool(int parallelism) {
        return Executors.newFixedThreadPool(
                parallelism,
                task -> {
                    Thread thread = new Thread(task, "x3-tts-synthesis");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static void closeResources(ExecutorService pool, TtsProvider provider)
            throws Exception {
        pool.shutdownNow();
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (provider instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private Path buildPipeline(
            BuilderOptions options,
            BuildProgress progress,
            BuildControl control,
            BookModel.Book book,
            SentenceSegmenter segmenter,
            BookAnalysis analysis,
            TtsProvider tts,
            ExecutorService synthesisPool,
            Path work)
            throws Exception {
        CacheAnalysis cache = analyzeCache(book, segmenter, tts, options);
        reportEstimate(options, progress, analysis, cache, tts.parallelism());
        progress.update(9, engineMessage(options, tts));
        FfmpegAudioEncoder encoder = new FfmpegAudioEncoder(options.ffmpeg, projectRoot);
        Pipeline pipeline =
                new Pipeline(
                        options,
                        progress,
                        control,
                        tts,
                        synthesisPool,
                        segmenter,
                        work,
                        analysis,
                        cache);
        startPipeline(pipeline);
        scheduleBook(book, pipeline);
        reportFullCache(pipeline);
        encodeChapters(pipeline, encoder);
        writePackage(book, pipeline, encoder);
        new BuilderCacheManager(projectRoot).afterSuccessfulBuild(work, progress);
        progress.update(100, "转换完成");
        return options.output.toAbsolutePath();
    }

    private CacheAnalysis analyzeCache(
            BookModel.Book book,
            SentenceSegmenter segmenter,
            TtsProvider tts,
            BuilderOptions options)
            throws Exception {
        CacheAnalysis result = new CacheAnalysis();
        for (BookModel.Volume volume : book.volumes) {
            for (BookModel.Chapter chapter : volume.chapters) {
                for (SentenceSegmenter.Segment segment : segmenter.segment(chapter)) {
                    int characters = codePoints(segment.text);
                    if (validWave(cachePath(tts, options, segment))) {
                        result.initiallyCached++;
                        result.cachedCharacters += characters;
                    } else {
                        result.uncachedCharacters += characters;
                    }
                }
            }
        }
        return result;
    }

    private static void reportEstimate(
            BuilderOptions options,
            BuildProgress progress,
            BookAnalysis analysis,
            CacheAnalysis cache,
            int parallelism) {
        long estimate =
                estimateSeconds(
                        options.tts,
                        cache.uncachedCharacters,
                        cache.initiallyCached,
                        analysis.chapterCount,
                        options.rate,
                        parallelism);
        progress.estimateSeconds(estimate);
        progress.update(
                8,
                "按文本长度与缓存初步估算：约 "
                        + humanDuration(estimate)
                        + "（未缓存 "
                        + cache.uncachedCharacters
                        + " 字；运行中按实际吞吐校准）");
    }

    private static String engineMessage(BuilderOptions options, TtsProvider tts) {
        if (options.tts.equalsIgnoreCase("melo")) {
            return "准备 MeloTTS（" + tts.parallelism() + " 个工作进程；首次加载模型可能需要一些时间）";
        }
        if (options.tts.equalsIgnoreCase("edge")) {
            return "准备 Edge 在线语音（" + tts.parallelism() + " 个并发请求）";
        }
        String label = TtsProviderCatalog.entry(options.tts).label();
        if (tts.parallelism() > 1) {
            return "准备 " + label + "（" + tts.parallelism() + " 个工作进程）";
        }
        return "准备语音引擎：" + label;
    }

    private static void startPipeline(Pipeline pipeline) {
        pipeline.progress.update(
                pipeline.percent(),
                "语音流水线已启动：已完成句子立即落入断点缓存，已编码 Track 可直接复用");
    }

    private void scheduleBook(BookModel.Book book, Pipeline pipeline) throws Exception {
        for (BookModel.Volume volume : book.volumes) {
            for (BookModel.Chapter chapter : volume.chapters) {
                scheduleChapter(chapter, pipeline);
            }
        }
    }

    private void scheduleChapter(BookModel.Chapter chapter, Pipeline pipeline) throws Exception {
        List<SentenceSegmenter.Segment> segments = pipeline.segmenter.segment(chapter);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Chapter has no speakable text: " + chapter.title);
        }
        Path sentenceDir = pipeline.work.resolve("sentences/" + chapter.chapterId);
        Files.createDirectories(sentenceDir);
        ChapterWork chapterWork = new ChapterWork(chapter, segments, sentenceDir);
        for (SentenceSegmenter.Segment segment : segments) {
            chapterWork.results.add(scheduleSegment(segment, sentenceDir, pipeline));
        }
        pipeline.chapterWorks.add(chapterWork);
    }

    private CompletableFuture<SynthesisResult> scheduleSegment(
            SentenceSegmenter.Segment segment, Path sentenceDir, Pipeline pipeline)
            throws Exception {
        Path cache = cachePath(pipeline.tts, pipeline.options, segment);
        Files.createDirectories(cache.getParent());
        if (validWave(cache)) {
            pipeline.initialCache.add(cache);
            return CompletableFuture.completedFuture(
                    new SynthesisResult(cache, WaveSupport.durationMs(cache)));
        }
        CompletableFuture<SynthesisResult> base = pipeline.pending.get(cache);
        if (base == null) {
            base = synthesisFuture(segment, sentenceDir, cache, pipeline);
            pipeline.pending.put(cache, base);
        }
        return progressFuture(base, segment, pipeline);
    }

    private CompletableFuture<SynthesisResult> synthesisFuture(
            SentenceSegmenter.Segment segment, Path sentenceDir, Path cache, Pipeline pipeline) {
        SynthesisRequest request =
                new SynthesisRequest(
                        segment.segmentId,
                        segment.text,
                        pipeline.options.voice,
                        pipeline.options.rate);
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return synthesizeToCache(
                                pipeline.tts,
                                request,
                                sentenceDir,
                                cache,
                                pipeline.control);
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                },
                pipeline.synthesisPool);
    }

    private static CompletableFuture<SynthesisResult> progressFuture(
            CompletableFuture<SynthesisResult> base,
            SentenceSegmenter.Segment segment,
            Pipeline pipeline) {
        int characters = codePoints(segment.text);
        int allSegments = pipeline.analysis.totalSegments - pipeline.cache.initiallyCached;
        return base.thenApply(
                result -> {
                    int done = pipeline.generatedSegments.incrementAndGet();
                    pipeline.completedCharacters.addAndGet(characters);
                    String message =
                            done == allSegments
                                    ? "语音生成完成：本次 "
                                            + done
                                            + " 句，复用缓存 "
                                            + pipeline.cache.initiallyCached
                                            + " 句"
                                    : null;
                    pipeline.progress.update(pipeline.percent(), message);
                    return result;
                });
    }

    private static void reportFullCache(Pipeline pipeline) {
        if (pipeline.cache.uncachedCharacters == 0) {
            pipeline.progress.update(
                    pipeline.percent(),
                    "命中完整语音缓存：已复用 " + pipeline.cache.initiallyCached + " 句，跳过语音生成");
        }
    }
    private void encodeChapters(Pipeline pipeline, FfmpegAudioEncoder encoder) throws Exception {
        int cachedSegments = 0;
        int chapterIndex = 0;
        for (ChapterWork chapterWork : pipeline.chapterWorks) {
            pipeline.control.checkpoint();
            chapterIndex++;
            reportChapter(pipeline, chapterIndex, "等待语音并准备 Track");
            ChapterAudio audio = awaitChapterAudio(chapterWork, pipeline);
            cachedSegments += audio.cachedSegments;
            reportChapter(
                    pipeline,
                    chapterIndex,
                    "拼接/复用并编码 M4A（缓存命中 " + cachedSegments + " 句）");
            encodeChapterParts(chapterWork, audio, chapterIndex, pipeline, encoder);
            pipeline.completedChapters.set(chapterIndex);
            pipeline.progress.update(pipeline.percent(), null);
        }
    }

    private static void reportChapter(Pipeline pipeline, int index, String action) {
        pipeline.progress.update(
                pipeline.percent(),
                "章节 " + index + "/" + pipeline.analysis.chapterCount + "：" + action);
    }

    private static ChapterAudio awaitChapterAudio(
            ChapterWork chapterWork, Pipeline pipeline) throws Exception {
        ChapterAudio result = new ChapterAudio();
        for (int index = 0; index < chapterWork.segments.size(); index++) {
            pipeline.control.checkpoint();
            SynthesisResult synthesis = await(chapterWork.results.get(index), pipeline.control);
            Path cache = synthesis.getWavPath();
            if (pipeline.initialCache.contains(cache)) {
                result.cachedSegments++;
            }
            result.waves.add(cache);
            result.durations.add(synthesis.getDurationMs());
        }
        return result;
    }

    private void encodeChapterParts(
            ChapterWork chapterWork,
            ChapterAudio audio,
            int chapterIndex,
            Pipeline pipeline,
            FfmpegAudioEncoder encoder)
            throws Exception {
        long maxTrackMs = pipeline.options.maxTrackMinutes * 60_000L;
        int start = 0;
        int partIndex = 0;
        while (start < chapterWork.segments.size()) {
            pipeline.control.checkpoint();
            Part part = nextPart(audio.durations, start, maxTrackMs, pipeline.options.pauseMs);
            pipeline.tracks.add(
                    trackArtifact(
                            chapterWork,
                            audio,
                            start,
                            partIndex,
                            part,
                            chapterIndex,
                            pipeline,
                            encoder));
            start = part.end;
            partIndex++;
        }
    }

    private static Part nextPart(
            List<Long> durations, int start, long maxTrackMs, int pauseMs) {
        int end = start;
        long expectedDuration = 0;
        while (end < durations.size()) {
            long added = durations.get(end) + (end > start ? pauseMs : 0);
            if (end > start && expectedDuration + added > maxTrackMs) {
                break;
            }
            expectedDuration += added;
            end++;
        }
        return new Part(end, expectedDuration);
    }

    private OpbookWriter.TrackArtifact trackArtifact(
            ChapterWork chapterWork,
            ChapterAudio audio,
            int start,
            int partIndex,
            Part part,
            int chapterIndex,
            Pipeline pipeline,
            FfmpegAudioEncoder encoder)
            throws Exception {
        BookModel.Chapter chapter = chapterWork.chapter;
        String baseId = chapter.chapterId.replace("chapter", "track");
        String trackId =
                partIndex == 0
                        ? baseId
                        : baseId + "-p" + String.format("%03d", partIndex + 1);
        Path m4a = pipeline.work.resolve(trackId + ".m4a");
        Path metadata = pipeline.work.resolve(trackId + ".track.properties");
        String signature =
                trackSignature(
                        pipeline.tts,
                        pipeline.options,
                        trackId,
                        audio.waves.subList(start, part.end),
                        audio.durations.subList(start, part.end));
        long combinedDuration =
                renderTrack(
                        m4a,
                        metadata,
                        signature,
                        trackId,
                        audio,
                        start,
                        part,
                        chapterIndex,
                        pipeline,
                        encoder);
        return artifact(
                chapterWork,
                audio.durations,
                start,
                part,
                trackId,
                m4a,
                combinedDuration,
                pipeline.options.pauseMs);
    }

    private long renderTrack(
            Path m4a,
            Path metadata,
            String signature,
            String trackId,
            ChapterAudio audio,
            int start,
            Part part,
            int chapterIndex,
            Pipeline pipeline,
            FfmpegAudioEncoder encoder)
            throws Exception {
        if (validTrack(m4a, metadata, signature)) {
            reportChapter(pipeline, chapterIndex, "复用已完成 Track " + trackId);
            return part.expectedDuration;
        }
        Path combined = pipeline.work.resolve(trackId + ".wav");
        try {
            long duration =
                    WaveSupport.concatenate(
                            new ArrayList<>(audio.waves.subList(start, part.end)),
                            pipeline.options.pauseMs,
                            combined);
            encoder.encodeWave(combined, m4a, pipeline.control);
            writeTrackMetadata(metadata, signature, duration);
            return duration;
        } finally {
            Files.deleteIfExists(combined);
        }
    }

    private static OpbookWriter.TrackArtifact artifact(
            ChapterWork chapterWork,
            List<Long> durations,
            int start,
            Part part,
            String trackId,
            Path m4a,
            long combinedDuration,
            int pauseMs) {
        OpbookWriter.TrackArtifact artifact = new OpbookWriter.TrackArtifact();
        artifact.trackId = trackId;
        artifact.chapter = chapterWork.chapter;
        artifact.m4a = m4a;
        artifact.durationMs = combinedDuration;
        long cursor = 0;
        for (int index = start; index < part.end; index++) {
            OpbookWriter.TimedSegment timed = new OpbookWriter.TimedSegment();
            timed.segment = chapterWork.segments.get(index);
            timed.startMs = cursor;
            timed.endMs =
                    index + 1 == part.end ? combinedDuration : cursor + durations.get(index);
            artifact.segments.add(timed);
            cursor = timed.endMs + (index + 1 < part.end ? pauseMs : 0);
        }
        return artifact;
    }
    private void writePackage(
            BookModel.Book book, Pipeline pipeline, FfmpegAudioEncoder encoder) throws Exception {
        pipeline.control.checkpoint();
        pipeline.progress.update(91, "生成并编码封面");
        Path cover = pipeline.work.resolve("cover.webp");
        encoder.encodeCover(pipeline.options.cover, cover, pipeline.control);
        pipeline.control.checkpoint();
        pipeline.progress.update(95, "写入 manifest、正文、Timeline 与校验和");
        writePackageWithRetry(book, pipeline, cover, encoder.version());
    }

    private static void writePackageWithRetry(
            BookModel.Book book, Pipeline pipeline, Path cover, String encoderVersion)
            throws Exception {
        Exception packageFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            pipeline.control.checkpoint();
            try {
                new OpbookWriter()
                        .write(
                                book,
                                pipeline.tracks,
                                cover,
                                pipeline.options.output.toAbsolutePath(),
                                pipeline.work.resolve("staging"),
                                pipeline.options.createdAt,
                                encoderVersion);
                return;
            } catch (Exception failure) {
                packageFailure = failure;
                if (attempt == 1) {
                    pipeline.progress.update(95, "打包步骤失败，保留断点与语音缓存并自动重试一次");
                }
            }
        }
        throw packageFailure;
    }

    private static final class BookAnalysis {

        int chapterCount;

        int totalSegments;

        long totalCharacters;
    }

    private static final class CacheAnalysis {

        int initiallyCached;

        long cachedCharacters;

        long uncachedCharacters;
    }

    private static final class ChapterAudio {

        final List<Path> waves = new ArrayList<>();

        final List<Long> durations = new ArrayList<>();

        int cachedSegments;
    }

    private static final class Part {

        final int end;

        final long expectedDuration;

        Part(int end, long expectedDuration) {
            this.end = end;
            this.expectedDuration = expectedDuration;
        }
    }

    private static final class Pipeline {

        final BuilderOptions options;

        final BuildProgress progress;

        final BuildControl control;

        final TtsProvider tts;

        final ExecutorService synthesisPool;

        final SentenceSegmenter segmenter;

        final Path work;

        final BookAnalysis analysis;

        final CacheAnalysis cache;

        final AtomicInteger generatedSegments = new AtomicInteger();

        final AtomicInteger completedChapters = new AtomicInteger();

        final AtomicLong completedCharacters;

        final List<ChapterWork> chapterWorks = new ArrayList<>();

        final Map<Path, CompletableFuture<SynthesisResult>> pending = new LinkedHashMap<>();

        final Set<Path> initialCache = new HashSet<>();

        final List<OpbookWriter.TrackArtifact> tracks = new ArrayList<>();

        Pipeline(
                BuilderOptions options,
                BuildProgress progress,
                BuildControl control,
                TtsProvider tts,
                ExecutorService synthesisPool,
                SentenceSegmenter segmenter,
                Path work,
                BookAnalysis analysis,
                CacheAnalysis cache) {
            this.options = options;
            this.progress = progress;
            this.control = control;
            this.tts = tts;
            this.synthesisPool = synthesisPool;
            this.segmenter = segmenter;
            this.work = work;
            this.analysis = analysis;
            this.cache = cache;
            completedCharacters = new AtomicLong(cache.cachedCharacters);
        }

        int percent() {
            return pipelinePercent(
                    completedCharacters.get(),
                    analysis.totalCharacters,
                    completedChapters.get(),
                    analysis.chapterCount);
        }
    }
    private SynthesisResult synthesizeToCache(
            TtsProvider tts,
            SynthesisRequest request,
            Path sentenceDir,
            Path cache,
            BuildControl control)
            throws Exception {
        control.checkpoint();
        SynthesisResult generated = tts.synthesize(request, sentenceDir);
        Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.copy(generated.getWavPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomic(temporary, cache);
            return new SynthesisResult(cache, generated.getDurationMs());
        } finally {
            Files.deleteIfExists(temporary);
            if (!generated
                    .getWavPath()
                    .toAbsolutePath()
                    .normalize()
                    .equals(cache.toAbsolutePath().normalize())) {
                Files.deleteIfExists(generated.getWavPath());
            }
        }
    }

    private Path cachePath(
            TtsProvider tts, BuilderOptions options, SentenceSegmenter.Segment segment) {
        String cacheId =
                Ids.sha256(
                                tts.id()
                                        + "\n"
                                        + options.voice
                                        + "\n"
                                        + options.rate
                                        + "\n"
                                        + segment.text)
                        .substring(0, 24);
        return BuilderWorkDirectory.resolve(projectRoot).resolve("cache/" + cacheId + ".wav");
    }

    private static String trackSignature(
            TtsProvider tts,
            BuilderOptions options,
            String trackId,
            List<Path> waves,
            List<Long> durations) {
        StringBuilder value =
                new StringBuilder(tts.id())
                        .append('|')
                        .append(options.voice)
                        .append('|')
                        .append(options.rate)
                        .append('|')
                        .append(options.pauseMs)
                        .append('|')
                        .append(trackId);
        for (int i = 0; i < waves.size(); i++) {
            value.append('|')
                    .append(waves.get(i).getFileName())
                    .append(':')
                    .append(durations.get(i));
        }
        return Ids.sha256(value.toString());
    }

    private static boolean validTrack(Path m4a, Path metadata, String signature) {
        try {
            if (!Files.isRegularFile(m4a)
                    || Files.size(m4a) < 512
                    || !Files.isRegularFile(metadata)) {
                return false;
            }
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(metadata)) {
                values.load(input);
            }
            return signature.equals(values.getProperty("signature"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeTrackMetadata(Path metadata, String signature, long duration)
            throws Exception {
        Properties values = new Properties();
        values.setProperty("signature", signature);
        values.setProperty("durationMs", Long.toString(duration));
        Path temporary =
                metadata.resolveSibling(metadata.getFileName() + ".tmp-" + UUID.randomUUID());
        try (OutputStream output = Files.newOutputStream(temporary)) {
            values.store(output, "Reusable encoded Track");
        }
        try {
            moveAtomic(temporary, metadata);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomic(Path source, Path target) throws Exception {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean validWave(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 46;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    static int synthesisPercent(long completed, long total) {
        return pipelinePercent(completed, total, 0, 1);
    }

    static int pipelinePercent(
            long completedCharacters,
            long totalCharacters,
            int completedChapters,
            int totalChapters) {
        double synthesis =
                totalCharacters <= 0
                        ? 1.0
                        : Math.max(
                                0.0, Math.min(1.0, completedCharacters / (double) totalCharacters));
        double encoding =
                totalChapters <= 0
                        ? 1.0
                        : Math.max(0.0, Math.min(1.0, completedChapters / (double) totalChapters));
        return Math.min(
                90, 10 + (int) Math.round(synthesis * 68.0) + (int) Math.round(encoding * 12.0));
    }

    static long estimateSeconds(
            String provider,
            long uncachedCharacters,
            int cachedSegments,
            int chapters,
            double rate) {
        return estimateSeconds(provider, uncachedCharacters, cachedSegments, chapters, rate, 1);
    }

    static long estimateSeconds(
            String provider,
            long uncachedCharacters,
            int cachedSegments,
            int chapters,
            double rate,
            int parallelism) {
        TtsProviderCatalog.Entry profile = TtsProviderCatalog.entry(provider);
        double speed = profile.estimatedCharactersPerHour(parallelism) / 3600.0;
        long startup =
                uncachedCharacters == 0
                        ? 0
                        : "melo".equalsIgnoreCase(provider)
                                ? parallelism > 1 ? 38 : 25
                                : provider.startsWith("sherpa-")
                                        ? 20
                                        : "windows".equalsIgnoreCase(provider) ? 3 : 2;
        long synthesis = (long) Math.ceil(uncachedCharacters / (speed * Math.max(0.5, rate)));
        long cacheScan = Math.max(1, (cachedSegments + 249) / 250);
        long encode = Math.max(8, chapters * 3L);
        return Math.max(10, startup + synthesis + cacheScan + encode + 5);
    }

    private static SynthesisResult await(
            CompletableFuture<SynthesisResult> future, BuildControl control) throws Exception {
        while (true) {
            control.checkpoint();
            try {
                return future.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(cause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                control.requestStop();
                control.checkpoint();
            }
        }
    }

    private static String humanDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remaining = seconds % 60;
        return hours > 0
                ? String.format("%d 小时 %02d 分 %02d 秒", hours, minutes, remaining)
                : String.format("%02d 分 %02d 秒", minutes, remaining);
    }

    private static BookModel.Book parse(BuilderOptions options) throws Exception {
        List<SourceParser> parsers = List.of(new TextSourceParser(), new EpubSourceParser());
        BookModel.Book book = new BookModel.Book();
        book.title =
                options.title != null
                        ? options.title
                        : stripExtension(options.inputs.get(0).getFileName().toString());
        book.authors.add(effectiveAuthor(options.author));
        book.language = options.language;
        StringBuilder fingerprint = new StringBuilder(book.title);
        for (int i = 0; i < options.inputs.size(); i++) {
            Path source = options.inputs.get(i).toAbsolutePath();
            SourceParser parser =
                    parsers.stream()
                            .filter(candidate -> candidate.supports(source))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unsupported source: " + source));
            BookModel.Volume volume = parser.parse(source, i);
            book.volumes.add(volume);
            fingerprint.append('|').append(Ids.sha256(source));
        }
        book.bookId = Ids.bookId(book.title, fingerprint.toString());
        return book;
    }

    private static void validate(BuilderOptions options) {
        requireInputsAndOutput(options);
        requireRanges(options);
        requireInputFiles(options);
    }

    private static void requireInputsAndOutput(BuilderOptions options) {
        if (options.inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one --input is required");
        }
        if (options.output == null) {
            throw new IllegalArgumentException("--output is required");
        }
    }

    private static void requireRanges(BuilderOptions options) {
        requireRange("--pause-ms", options.pauseMs, 0, 10000);
        requireRange("--max-track-minutes", options.maxTrackMinutes, 1, 120);
        requireRange("--melo-workers", options.meloWorkers, 1, 2);
        requireRange("--sherpa-workers", options.sherpaWorkers, 1, 4);
        requireRange("--windows-workers", options.windowsWorkers, 1, 12);
        requireRange("--edge-workers", options.edgeWorkers, 1, 12);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be " + minimum + ".." + maximum);
        }
    }

    private static void requireInputFiles(BuilderOptions options) {
        for (Path path : options.inputs) {
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Input not found: " + path);
            }
        }
        if (options.cover != null && !Files.isRegularFile(options.cover)) {
            throw new IllegalArgumentException("Cover not found: " + options.cover);
        }
    }
    static String effectiveAuthor(String author) {
        return author == null || author.isBlank() ? "未署名" : author.trim();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
