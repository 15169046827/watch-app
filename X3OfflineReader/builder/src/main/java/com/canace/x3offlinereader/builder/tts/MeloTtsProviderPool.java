package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** A small adaptive pool: two CPU models only when the machine has enough CPU and free RAM. */
public final class MeloTtsProviderPool implements TtsProvider, AutoCloseable {
    static final long GIB = 1024L * 1024L * 1024L;
    private final List<MeloTtsProvider> providers = new ArrayList<>();
    private final BlockingQueue<MeloTtsProvider> available;

    public MeloTtsProviderPool(Path projectRoot, Path configuredPython, int maximumWorkers) {
        int workers =
                recommendedParallelism(
                        totalMemory(),
                        freeMemory(),
                        Runtime.getRuntime().availableProcessors(),
                        maximumWorkers);
        // This CPU benchmarked best with eight OpenMP threads per concurrent model. A lone
        // model may use the whole host; more workers are capped to avoid oversubscription.
        int processors = Runtime.getRuntime().availableProcessors();
        int torchThreads =
                workers == 1 ? Math.max(2, processors) : Math.max(2, Math.min(8, processors));
        for (int index = 0; index < workers; index++) {
            providers.add(new MeloTtsProvider(projectRoot, configuredPython, torchThreads));
        }
        available = new ArrayBlockingQueue<>(workers);
        available.addAll(providers);
    }

    @Override
    public String id() {
        return "melotts";
    }

    @Override
    public boolean isAvailable() {
        return !providers.isEmpty() && providers.get(0).isAvailable();
    }

    @Override
    public List<String> voices() {
        return providers.isEmpty() ? List.of() : providers.get(0).voices();
    }

    @Override
    public int parallelism() {
        return providers.size();
    }

    @Override
    public SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory)
            throws Exception {
        MeloTtsProvider provider = available.take();
        try {
            return provider.synthesize(request, workingDirectory);
        } finally {
            available.put(provider);
        }
    }

    static int recommendedParallelism(
            long totalBytes, long freeBytes, int processors, int maximumWorkers) {
        int maximum = Math.max(1, Math.min(2, maximumWorkers));
        if (maximum >= 2 && totalBytes >= 24 * GIB && freeBytes >= 10 * GIB && processors >= 12) {
            return 2;
        }
        return 1;
    }

    private static OperatingSystemMXBean os() {
        return (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    private static long totalMemory() {
        return os().getTotalMemorySize();
    }

    private static long freeMemory() {
        return os().getFreeMemorySize();
    }

    @Override
    public void close() {
        for (MeloTtsProvider provider : providers) {
            provider.close();
        }
    }
}
