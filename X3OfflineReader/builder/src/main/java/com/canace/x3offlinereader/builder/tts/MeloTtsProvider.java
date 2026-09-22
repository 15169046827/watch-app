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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Long-lived local MeloTTS worker; no network API or credential is used. */
public final class MeloTtsProvider implements TtsProvider, AutoCloseable {

    static final int MAX_CHUNK_CODE_POINTS = 120;

    private final Path python;

    private final Path worker;

    private final int torchThreads;

    private final Deque<String> stderrTail = new ArrayDeque<>();

    private Process process;

    private BufferedReader output;

    private BufferedWriter input;

    public MeloTtsProvider(Path projectRoot, Path configuredPython) {
        this(projectRoot, configuredPython, 0);
    }

    MeloTtsProvider(Path projectRoot, Path configuredPython, int torchThreads) {
        String environment = System.getenv("X3_MELO_PYTHON");
        python =
                (configuredPython != null
                                ? configuredPython
                                : environment != null && !environment.isBlank()
                                        ? Path.of(environment)
                                        : projectRoot.resolve(".tools/melo/python.exe"))
                        .toAbsolutePath();
        worker = projectRoot.resolve("builder/melo/worker.py").toAbsolutePath();
        this.torchThreads = torchThreads;
    }

    @Override
    public String id() {
        return "melotts";
    }

    @Override
    public boolean isAvailable() {
        return Files.isRegularFile(python) && Files.isRegularFile(worker);
    }

    @Override
    public List<String> voices() {
        return List.of("ZH");
    }

    @Override
    public synchronized SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory)
            throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "MeloTTS is not installed. Run builder/melo/setup-melo.ps1 or pass"
                            + " --melo-python");
        }
        Files.createDirectories(workingDirectory);
        Path wave = workingDirectory.resolve(request.getSegmentId() + ".wav").toAbsolutePath();
        synthesizeRecovering(
                request.getSegmentId(),
                request.getText(),
                request.getVoiceId(),
                request.getRate(),
                workingDirectory,
                wave,
                0);
        if (!Files.isRegularFile(wave) || Files.size(wave) <= 46) {
            throw new IllegalStateException("MeloTTS 未生成有效 WAV");
        }
        return new SynthesisResult(wave, WaveSupport.durationMs(wave));
    }

    private void synthesizeRecovering(
            String id,
            String text,
            String voice,
            double rate,
            Path directory,
            Path outputWave,
            int depth)
            throws Exception {
        int length = text.codePointCount(0, text.length());
        if (length > MAX_CHUNK_CODE_POINTS) {
            synthesizeParts(
                    id,
                    splitText(text, MAX_CHUNK_CODE_POINTS),
                    voice,
                    rate,
                    directory,
                    outputWave,
                    depth);
            return;
        }
        try {
            requestSingle(outputWave, text, voice, rate);
        } catch (Exception failure) {
            if (length > 24 && depth < 6) {
                synthesizeParts(
                        id,
                        splitText(text, Math.max(16, length / 2)),
                        voice,
                        rate,
                        directory,
                        outputWave,
                        depth + 1);
                return;
            }
            restartWorker();
            try {
                requestSingle(outputWave, text, voice, rate);
            } catch (Exception retryFailure) {
                throw new IllegalStateException(
                        "MeloTTS 语音生成失败（句长 "
                                + length
                                + " 字，声音 "
                                + voice
                                + "）："
                                + retryFailure.getMessage(),
                        retryFailure);
            }
        }
    }

    private void synthesizeParts(
            String id,
            List<String> pieces,
            String voice,
            double rate,
            Path directory,
            Path outputWave,
            int depth)
            throws Exception {
        List<Path> waves = new ArrayList<>();
        try {
            for (int index = 0; index < pieces.size(); index++) {
                Path part =
                        directory
                                .resolve(
                                        id
                                                + "-melo-"
                                                + depth
                                                + "-"
                                                + String.format("%03d", index + 1)
                                                + ".wav")
                                .toAbsolutePath();
                synthesizeRecovering(
                        id + "-" + (index + 1),
                        pieces.get(index),
                        voice,
                        rate,
                        directory,
                        part,
                        depth + 1);
                waves.add(part);
            }
            WaveSupport.concatenate(waves, 0, outputWave);
        } finally {
            for (Path wave : waves) {
                Files.deleteIfExists(wave);
            }
        }
    }

    private void requestSingle(Path wave, String text, String voice, double rate) throws Exception {
        start();
        Files.deleteIfExists(wave);
        String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        input.write(wave + "\t" + voice + "\t" + rate + "\t" + encoded);
        input.newLine();
        input.flush();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(12);
        while (!output.ready()) {
            if (process == null || !process.isAlive()) {
                throw new IllegalStateException("worker 已停止" + diagnosticSuffix());
            }
            if (System.nanoTime() >= deadline) {
                restartWorker();
                throw new IllegalStateException("worker 超过 12 分钟未返回，已重启并准备重试");
            }
            Thread.sleep(250);
        }
        String reply = output.readLine();
        if (reply == null || !reply.startsWith("OK")) {
            throw new IllegalStateException(
                    (reply == null ? "worker 已停止" : reply) + diagnosticSuffix());
        }
        if (!Files.isRegularFile(wave) || Files.size(wave) <= 46) {
            throw new IllegalStateException("worker 返回成功但未生成有效 WAV" + diagnosticSuffix());
        }
    }

    private void start() throws Exception {
        if (process != null && process.isAlive()) {
            return;
        }
        synchronized (stderrTail) {
            stderrTail.clear();
        }
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-u", worker.toString());
        Path environment = python.getParent();
        builder.environment().put("HF_HOME", environment.resolve("huggingface").toString());
        builder.environment().put("NLTK_DATA", environment.resolve("nltk_data").toString());
        builder.environment().put("HF_HUB_DISABLE_SYMLINKS_WARNING", "1");
        builder.environment().put("HF_HUB_OFFLINE", "1");
        builder.environment().put("TRANSFORMERS_OFFLINE", "1");
        builder.environment().put("HF_DATASETS_OFFLINE", "1");
        if (torchThreads > 0) {
            String limit = Integer.toString(torchThreads);
            builder.environment().put("OMP_NUM_THREADS", limit);
            builder.environment().put("MKL_NUM_THREADS", limit);
        }
        process = builder.start();
        output =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        input =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader errors =
                new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        Thread drainer = new Thread(() -> drainErrors(errors), "x3-melo-diagnostics");
        drainer.setDaemon(true);
        drainer.start();
        String ready = output.readLine();
        if (!"READY".equals(ready)) {
            String detail = ready == null ? "worker 已停止" : ready;
            close();
            throw new IllegalStateException("MeloTTS worker 启动失败：" + detail + diagnosticSuffix());
        }
    }

    private void drainErrors(BufferedReader errors) {
        try {
            String line;
            while ((line = errors.readLine()) != null) {
                String clean = line.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
                if (clean.length() > 400) {
                    clean = clean.substring(0, 400);
                }
                synchronized (stderrTail) {
                    stderrTail.addLast(clean);
                    while (stderrTail.size() > 12) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String diagnosticSuffix() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            StringBuilder text = new StringBuilder("；诊断：");
            int skip = Math.max(0, stderrTail.size() - 5);
            int index = 0;
            for (String line : stderrTail) {
                if (index++ < skip) {
                    continue;
                }
                if (!line.isBlank()) {
                    text.append(' ').append(line);
                }
            }
            return text.toString();
        }
    }

    private void restartWorker() {
        close();
    }

    static List<String> splitText(String text, int maximumCodePoints) {
        if (maximumCodePoints < 2) {
            throw new IllegalArgumentException("maximumCodePoints must be at least 2");
        }
        List<String> result = new ArrayList<>();
        int total = text.codePointCount(0, text.length());
        int start = 0;
        while (start < total) {
            int end = Math.min(total, start + maximumCodePoints);
            if (end < total) {
                int minimum = start + maximumCodePoints / 2;
                for (int cursor = end - 1; cursor >= minimum; cursor--) {
                    int charIndex = text.offsetByCodePoints(0, cursor);
                    int cp = text.codePointAt(charIndex);
                    if ("，、,：:；;。！？!? \n\t".indexOf(cp) >= 0) {
                        end = cursor + 1;
                        break;
                    }
                }
            }
            int from = text.offsetByCodePoints(0, start);
            int to = text.offsetByCodePoints(0, end);
            String piece = text.substring(from, to);
            if (!piece.isBlank()) {
                result.add(piece);
            }
            start = end;
        }
        return result;
    }

    @Override
    public synchronized void close() {
        try {
            if (input != null && process != null && process.isAlive()) {
                input.write("QUIT");
                input.newLine();
                input.flush();
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null) {
                try {
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                process = null;
                input = null;
                output = null;
            }
        }
    }
}
