package com.canace.x3offlinereader.builder;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** JSON-lines boundary used by the local Vue/Tauri shell. */
final class BuilderBridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Object OUTPUT_LOCK = new Object();

    static final class Request {

        public List<String> inputs = new ArrayList<>();

        public String output;

        public String title;

        public String author = "";

        public String language = "zh-CN";

        public String tts = "melo";

        public String voice = "ZH";

        public String cover;

        public String controlFile;

        public int meloWorkers = 2;

        public int edgeWorkers = 8;

        public int sherpaWorkers = 4;

        public int windowsWorkers = 2;

        public int pauseMs = 250;

        public int maxTrackMinutes = 20;

        public double rate = 1.0;

        public boolean separate;

        public boolean outputToSource;

        public boolean allowOnlineTts;

        public boolean resume;
    }

    static int run(Path root, String[] args) throws Exception {
        return switch (args[0]) {
            case "bridge-edge-update" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("bridge-edge-update requires action");
                }
                emit(EdgeClientUpdater.run(root, args[1]));
                yield 0;
            }
            case "bridge-state" -> {
                emit(state(root));
                yield 0;
            }
            case "bridge-preview" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException(
                            "bridge-preview requires provider and voice");
                }
                emit(preview(root, args[1], args[2]));
                yield 0;
            }
            case "bridge-cache" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("bridge-cache requires action");
                }
                emit(cache(root, args[1]));
                yield 0;
            }
            case "bridge-build" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("bridge-build requires request JSON path");
                }
                yield build(root, Path.of(args[1]));
            }
            default -> throw new IllegalArgumentException("Unknown bridge command: " + args[0]);
        };
    }

    private static ObjectNode state(Path root) {
        ObjectNode state = JSON.createObjectNode();
        state.put("version", "0.7.0");
        ArrayNode providers = state.putArray("providers");
        for (TtsProviderCatalog.Entry entry : TtsProviderCatalog.guiEntries()) {
            ObjectNode node = providers.addObject();
            node.put("id", entry.id());
            node.put("label", entry.label());
            node.put("online", entry.online());
            node.put("defaultVoice", entry.defaultVoice());
            node.put("throughputLabel", entry.throughputLabel());
            node.put("benchmarkCharactersPerHour", entry.optimizedCharactersPerHour());
            node.put("optimizedParallelism", entry.optimizedParallelism());
            ArrayNode voices = node.putArray("voices");
            boolean livePreview = false;
            List<String> values = VoicePreviewLibrary.cachedVoices(root, entry.id());
            if (values.isEmpty()) {
                try {
                    TtsProvider engine = TtsProviderCatalog.previewProvider(entry.id(), root, null);
                    try {
                        livePreview = "windows".equals(entry.id()) && engine.isAvailable();
                        values = engine.voices();
                    } finally {
                        if (engine instanceof AutoCloseable closeable) {
                            closeable.close();
                        }
                    }
                } catch (Exception ignored) {
                    values = List.of(entry.defaultVoice());
                }
            }
            for (String voice : values) {
                ObjectNode item = voices.addObject();
                boolean cached = VoicePreviewLibrary.available(root, entry.id(), voice);
                String previewType = cached ? "cached" : livePreview ? "live" : "none";
                item.put("id", voice);
                item.put("preview", !"none".equals(previewType));
                item.put("previewType", previewType);
            }
        }
        appendCache(state, new BuilderCacheManager(root).stats());
        new BuildCheckpointStore(root)
                .latest()
                .ifPresent(
                        checkpoint -> {
                            ObjectNode task = state.putObject("checkpoint");
                            task.put("id", checkpoint.id());
                            task.put("state", checkpoint.state());
                            task.put("percent", checkpoint.percent());
                            task.put("message", checkpoint.message());
                            task.put("elapsedSeconds", checkpoint.elapsedSeconds());
                            task.put("provider", checkpoint.options().tts);
                            task.put(
                                    "online",
                                    TtsProviderCatalog.entry(checkpoint.options().tts).online());
                            task.put(
                                    "output",
                                    checkpoint.options().output == null
                                            ? ""
                                            : checkpoint.options().output.toString());
                            ArrayNode inputs = task.putArray("inputs");
                            checkpoint
                                    .options()
                                    .inputs
                                    .forEach(path -> inputs.add(path.toString()));
                        });
        return state;
    }

    private static ObjectNode preview(Path root, String provider, String voice) throws Exception {
        byte[] audio;
        if (VoicePreviewLibrary.available(root, provider, voice)) {
            audio = Files.readAllBytes(VoicePreviewLibrary.path(root, provider, voice));
        } else {
            if (!"windows".equalsIgnoreCase(provider)) {
                throw new IllegalArgumentException("该音色没有预生成试听");
            }
            Path workRoot = BuilderWorkDirectory.resolve(root);
            Files.createDirectories(workRoot);
            Path work = Files.createTempDirectory(workRoot, "windows-preview-");
            TtsProvider engine = TtsProviderCatalog.previewProvider(provider, root, null);
            try {
                if (!engine.isAvailable() || !engine.voices().contains(voice)) {
                    throw new IllegalArgumentException("该 Windows 音色不可用");
                }
                SynthesisResult generated =
                        engine.synthesize(
                                new SynthesisRequest(
                                        "preview",
                                        VoicePreviewLibrary.sentence(provider),
                                        voice,
                                        VoicePreviewLibrary.RATE),
                                work);
                audio = Files.readAllBytes(generated.getWavPath());
            } finally {
                if (engine instanceof AutoCloseable closeable) {
                    closeable.close();
                }
                try (var paths = Files.walk(work)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("mimeType", "audio/wav");
        result.put("audioBase64", Base64.getEncoder().encodeToString(audio));
        return result;
    }

    private static ObjectNode cache(Path root, String action) throws Exception {
        BuilderCacheManager manager = new BuilderCacheManager(root);
        BuilderCacheManager.PruneResult result = switch (action) {
            case "prune" ->
                    manager.pruneSentenceCache(
                            BuilderCacheManager.DEFAULT_MAX_BYTES,
                            BuilderCacheManager.DEFAULT_MAX_AGE);
            case "stale" -> manager.clearStaleWork(Duration.ofDays(30));
            case "clear" -> manager.clearSentenceCache();
            default -> new BuilderCacheManager.PruneResult(0, 0);
        };
        ObjectNode response = JSON.createObjectNode();
        response.put("deletedFiles", result.deletedFiles());
        response.put("deletedBytes", result.deletedBytes());
        appendCache(response, manager.stats());
        return response;
    }

    private static int build(Path root, Path requestFile) throws Exception {
        Path requestRoot = BuilderWorkDirectory.resolve(root).resolve("ui-requests");
        Path normalized = requestFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(requestRoot) || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Unsafe or missing bridge request");
        }
        Request request = JSON.readValue(normalized.toFile(), Request.class);
        Files.deleteIfExists(normalized);
        Path control =
                Path.of(required(request.controlFile, "controlFile")).toAbsolutePath().normalize();
        if (!control.startsWith(requestRoot)) {
            throw new IllegalArgumentException("Unsafe control path");
        }
        Files.deleteIfExists(control);
        BuildCheckpointStore checkpoints = new BuildCheckpointStore(root);
        BuildCheckpointStore.Checkpoint checkpoint;
        BuilderOptions options;
        long previousElapsedSeconds;
        if (request.resume) {
            checkpoint =
                    checkpoints.latest().orElseThrow(() -> new IllegalStateException("没有可恢复任务"));
            options = checkpoint.options();
            previousElapsedSeconds = checkpoint.elapsedSeconds();
            checkpoint =
                    checkpoints.update(
                            checkpoint, "running", 0, "正在重新计算断点进度", previousElapsedSeconds);
        } else {
            options = options(request);
            checkpoint = checkpoints.create(options);
            previousElapsedSeconds = 0;
        }
        long sessionStartedNanos = System.nanoTime();
        AtomicReference<BuildCheckpointStore.Checkpoint> active = new AtomicReference<>(checkpoint);
        AtomicInteger lastPercent = new AtomicInteger(checkpoint.percent());
        AtomicLong estimate = new AtomicLong(-1);
        BuildControl buildControl = new BuildControl(control);
        List<String> outputs = new ArrayList<>();
        BuildProgress progress =
                new BuildProgress() {

                    @Override
                    public void update(int percent, String message) {
                        int safe =
                                lastPercent.accumulateAndGet(
                                        Math.max(0, Math.min(100, percent)), Math::max);
                        if (message != null) {
                            try {
                                active.set(
                                        checkpoints.update(
                                                active.get(),
                                                "running",
                                                safe,
                                                message,
                                                taskElapsedSeconds(
                                                        previousElapsedSeconds,
                                                        sessionStartedNanos)));
                            } catch (Exception ignored) {
                            }
                        }
                        ObjectNode event = JSON.createObjectNode();
                        event.put("type", "progress");
                        event.put("percent", safe);
                        if (message != null) {
                            event.put("message", message);
                        }
                        if (estimate.get() > 0) {
                            event.put("estimateSeconds", estimate.get());
                        }
                        emit(event);
                    }

                    @Override
                    public void estimateSeconds(long seconds) {
                        estimate.set(seconds);
                        ObjectNode event = JSON.createObjectNode();
                        event.put("type", "estimate");
                        event.put("seconds", seconds);
                        emit(event);
                    }
                };
        try {
            AudiobookBuilder builder = new AudiobookBuilder(root);
            if (options.separate) {
                int count = options.inputs.size();
                if (!options.outputToSource) {
                    Files.createDirectories(options.output);
                }
                for (int index = 0; index < count; index++) {
                    buildControl.checkpoint();
                    Path input = options.inputs.get(index);
                    BuilderOptions one = copy(options);
                    one.inputs.add(input);
                    one.title = null;
                    one.separate = false;
                    one.output =
                            options.outputToSource
                                    ? BuilderGui.sourceOutput(input, options.author)
                                    : options.output.resolve(
                                            BuilderGui.suggestedOutputName(
                                                    List.of(input), null, options.author));
                    int fileIndex = index;
                    BuildProgress scaled =
                            new BuildProgress() {

                                @Override
                                public void update(int value, String message) {
                                    progress.update((fileIndex * 100 + value) / count, message);
                                }

                                @Override
                                public void estimateSeconds(long seconds) {
                                    progress.estimateSeconds(seconds * (count - fileIndex));
                                }
                            };
                    outputs.add(builder.build(one, scaled, buildControl).toString());
                }
            } else {
                outputs.add(builder.build(options, progress, buildControl).toString());
            }
            long runElapsedSeconds = runElapsedSeconds(sessionStartedNanos);
            checkpoints.complete(active.get());
            emit(completionEvent(previousElapsedSeconds, runElapsedSeconds, outputs));
            return 0;
        } catch (BuildControl.PauseRequestedException paused) {
            String message = paused.getMessage();
            active.set(
                    checkpoints.update(
                            active.get(),
                            "paused",
                            lastPercent.get(),
                            message,
                            taskElapsedSeconds(previousElapsedSeconds, sessionStartedNanos)));
            ObjectNode event = JSON.createObjectNode();
            event.put("type", "paused");
            event.put("percent", lastPercent.get());
            event.put("message", message);
            emit(event);
            return 0;
        } catch (Exception failure) {
            active.set(
                    checkpoints.update(
                            active.get(),
                            "failed",
                            lastPercent.get(),
                            safeMessage(failure),
                            taskElapsedSeconds(previousElapsedSeconds, sessionStartedNanos)));
            ObjectNode event = JSON.createObjectNode();
            event.put("type", "failed");
            event.put("percent", lastPercent.get());
            event.put("message", safeMessage(failure));
            emit(event);
            return 2;
        } finally {
            Files.deleteIfExists(control);
        }
    }

    private static BuilderOptions options(Request request) {
        BuilderOptions options = new BuilderOptions();
        request.inputs.forEach(value -> options.inputs.add(Path.of(value)));
        options.title = blank(request.title);
        options.author = request.author == null ? "" : request.author.trim();
        options.language = request.language;
        options.tts = request.tts;
        options.voice = request.voice;
        options.meloWorkers = request.meloWorkers;
        options.edgeWorkers = request.edgeWorkers;
        options.sherpaWorkers = request.sherpaWorkers;
        options.windowsWorkers = request.windowsWorkers;
        options.rate = request.rate;
        options.pauseMs = request.pauseMs;
        options.maxTrackMinutes = request.maxTrackMinutes;
        options.cover = blank(request.cover) == null ? null : Path.of(request.cover);
        options.separate = request.separate;
        options.outputToSource = request.outputToSource;
        options.allowOnlineTts = request.allowOnlineTts;
        if (options.inputs.isEmpty()) {
            throw new IllegalArgumentException("请添加 EPUB/TXT 来源");
        }
        if (request.outputToSource) {
            options.output =
                    request.separate
                            ? options.inputs.get(0).toAbsolutePath().getParent()
                            : BuilderGui.mergedSourceOutput(
                                    options.inputs, options.title, options.author);
        } else {
            options.output = Path.of(required(request.output, "output"));
        }
        return options;
    }

    private static BuilderOptions copy(BuilderOptions source) {
        BuilderOptions copy = new BuilderOptions();
        copy.title = source.title;
        copy.author = source.author;
        copy.language = source.language;
        copy.tts = source.tts;
        copy.voice = source.voice;
        copy.meloPython = source.meloPython;
        copy.meloWorkers = source.meloWorkers;
        copy.edgeWorkers = source.edgeWorkers;
        copy.sherpaWorkers = source.sherpaWorkers;
        copy.windowsWorkers = source.windowsWorkers;
        copy.rate = source.rate;
        copy.pauseMs = source.pauseMs;
        copy.maxTrackMinutes = source.maxTrackMinutes;
        copy.ffmpeg = source.ffmpeg;
        copy.cover = source.cover;
        copy.createdAt = source.createdAt;
        copy.outputToSource = source.outputToSource;
        copy.allowOnlineTts = source.allowOnlineTts;
        return copy;
    }

    private static void appendCache(ObjectNode node, BuilderCacheManager.Stats stats) {
        ObjectNode cache = node.putObject("cache");
        cache.put("sentenceFiles", stats.sentenceFiles());
        cache.put("sentenceBytes", stats.sentenceBytes());
        cache.put("unfinishedDirectories", stats.unfinishedDirectories());
        cache.put("unfinishedBytes", stats.unfinishedBytes());
    }

    static ObjectNode completionEvent(
            long previousElapsedSeconds, long runElapsedSeconds, List<String> outputs) {
        long safeRun = Math.max(0, runElapsedSeconds);
        long safePrevious = Math.max(0, previousElapsedSeconds);
        ObjectNode complete = JSON.createObjectNode();
        complete.put("type", "complete");
        complete.put("taskElapsedSeconds", safePrevious + safeRun);
        complete.put("runElapsedSeconds", safeRun);
        ArrayNode paths = complete.putArray("outputs");
        outputs.forEach(paths::add);
        return complete;
    }

    private static long runElapsedSeconds(long startedNanos) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toSeconds());
    }

    private static long taskElapsedSeconds(long previousElapsedSeconds, long startedNanos) {
        return previousElapsedSeconds + runElapsedSeconds(startedNanos);
    }

    private static void emit(ObjectNode value) {
        synchronized (OUTPUT_LOCK) {
            try {
                System.out.println(JSON.writeValueAsString(value));
                System.out.flush();
            } catch (Exception ignored) {
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + field);
        }
        return value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null && value.getCause() != value) {
            value = value.getCause();
        }
        String message = value.getMessage();
        return message == null || message.isBlank() ? value.getClass().getSimpleName() : message;
    }
}
