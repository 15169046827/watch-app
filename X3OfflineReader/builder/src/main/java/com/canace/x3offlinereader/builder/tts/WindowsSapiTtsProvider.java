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

/** Long-lived Windows offline voice backed by 32-bit System.Speech. */
public final class WindowsSapiTtsProvider implements TtsProvider, AutoCloseable {
    private final Path root;
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private Process process;
    private BufferedReader output;
    private BufferedWriter input;

    public WindowsSapiTtsProvider() {
        this(Path.of("").toAbsolutePath());
    }

    public WindowsSapiTtsProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "windows-sapi-v2";
    }

    @Override
    public boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                && Files.isRegularFile(worker());
    }

    @Override
    public List<String> voices() {
        List<String> result = new ArrayList<>();
        if (!isAvailable()) {
            return result;
        }
        try {
            String script =
                    "$ProgressPreference='SilentlyContinue';"
                        + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"
                        + "Add-Type -AssemblyName System.Speech;"
                        + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                        + "$s.GetInstalledVoices()|ForEach-Object{"
                        + "$i=$_.VoiceInfo;if($i.Culture.Name -like 'zh-*'){$i.Name}};"
                        + "$s.Dispose()";
            Process query = runEncoded(script);
            String log = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (query.waitFor() != 0) {
                return result;
            }
            for (String line : log.split("\\R")) {
                String clean = line.trim();
                if (!clean.isBlank()
                        && !clean.startsWith("#< CLIXML")
                        && !clean.startsWith("<Objs")) {
                    result.add(clean);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    @Override
    public synchronized SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory)
            throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Windows 系统语音组件不可用");
        }
        start();
        Files.createDirectories(workingDirectory);
        Path wave = workingDirectory.resolve(request.getSegmentId() + ".wav").toAbsolutePath();
        Files.deleteIfExists(wave);
        int rate = (int) Math.max(-10, Math.min(10, Math.round((request.getRate() - 1.0) * 10)));
        input.write(encoded(wave.toString()));
        input.write('\t');
        input.write(encoded(request.getVoiceId()));
        input.write('\t');
        input.write(Integer.toString(rate));
        input.write('\t');
        input.write(encoded(request.getText()));
        input.newLine();
        input.flush();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(12);
        while (!output.ready()) {
            if (process == null || !process.isAlive()) {
                throw new IllegalStateException("Windows 语音 Worker 已停止" + diagnosticSuffix());
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Windows 语音 Worker 超过 12 分钟未返回");
            }
            Thread.sleep(100);
        }
        String reply = output.readLine();
        if (reply == null || !reply.startsWith("OK")) {
            throw new IllegalStateException(
                    (reply == null ? "Windows 语音 Worker 已停止" : reply) + diagnosticSuffix());
        }
        if (!Files.isRegularFile(wave) || Files.size(wave) <= 46) {
            throw new IllegalStateException("Windows 语音 Worker 未生成有效 WAV");
        }
        return new SynthesisResult(wave, WaveSupport.durationMs(wave));
    }

    private void start() throws Exception {
        if (process != null && process.isAlive()) {
            return;
        }
        synchronized (stderrTail) {
            stderrTail.clear();
        }
        process =
                new ProcessBuilder(
                                powerShell(),
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-ExecutionPolicy",
                                "Bypass",
                                "-File",
                                worker().toString())
                        .start();
        output =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        input =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader errors =
                new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        Thread drainer = new Thread(() -> drainErrors(errors), "x3-windows-speech-diagnostics");
        drainer.setDaemon(true);
        drainer.start();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (!output.ready()) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Windows 语音 Worker 启动失败" + diagnosticSuffix());
            }
            if (System.nanoTime() > deadline) {
                close();
                throw new IllegalStateException("Windows 语音 Worker 启动超时");
            }
            Thread.sleep(50);
        }
        if (!"READY".equals(output.readLine())) {
            close();
            throw new IllegalStateException("Windows 语音 Worker 启动失败" + diagnosticSuffix());
        }
    }

    private void drainErrors(BufferedReader errors) {
        try {
            String line;
            while ((line = errors.readLine()) != null) {
                String clean = line.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
                synchronized (stderrTail) {
                    stderrTail.addLast(clean);
                    while (stderrTail.size() > 8) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String diagnosticSuffix() {
        synchronized (stderrTail) {
            return stderrTail.isEmpty() ? "" : "；诊断：" + String.join(" ", stderrTail);
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Process runEncoded(String script) throws Exception {
        String value =
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder(
                        powerShell(),
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-EncodedCommand",
                        value)
                .redirectErrorStream(true)
                .start();
    }

    private Path worker() {
        return root.resolve("builder/windows-speech/worker.ps1");
    }

    private static String powerShell() {
        Path x86 =
                Path.of(
                        System.getenv().getOrDefault("WINDIR", "C:\\Windows"),
                        "SysWOW64",
                        "WindowsPowerShell",
                        "v1.0",
                        "powershell.exe");
        return Files.isRegularFile(x86) ? x86.toString() : "powershell.exe";
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
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
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
