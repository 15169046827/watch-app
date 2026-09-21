package com.canace.x3offlinereader.builder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

public final class BuilderMain {
    private BuilderMain() { }

    public static void main(String[] args) throws Exception {
        String[] normalized = normalizedArgs(args);
        if (helpRequested(normalized)) {
            usage();
            return;
        }
        if ("voices".equals(normalized[0])) {
            listVoices();
            return;
        }
        Path root = findProjectRoot();
        if ("migrate-work".equals(normalized[0])) {
            migrateWork(root, normalized);
            return;
        }
        BuilderWorkDirectory.initialize(root);
        if (normalized[0].startsWith("bridge-")) {
            runBridge(root, normalized);
        } else if ("gui".equals(normalized[0])) {
            javax.swing.SwingUtilities.invokeLater(() -> new BuilderGui(root).show());
        } else if ("build".equals(normalized[0])) {
            build(root, normalized);
        } else {
            throw new IllegalArgumentException("Unknown command: " + normalized[0]);
        }
    }

    private static String[] normalizedArgs(String[] args) {
        // Some jpackage versions inherit the primary GUI launcher's default argument.
        // Keep the additional console launcher usable even if that packaging quirk recurs.
        if (args.length > 1 && "gui".equals(args[0])) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return args;
    }

    private static boolean helpRequested(String[] args) {
        return args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0]);
    }

    private static void migrateWork(Path root, String[] args) throws Exception {
        if (args.length != 3 || !"--from".equals(args[1])) {
            throw new IllegalArgumentException("migrate-work requires --from <legacy-directory>");
        }
        BuilderWorkDirectory.MigrationResult result =
                BuilderWorkDirectory.migrate(
                        Path.of(args[2]), BuilderWorkDirectory.resolve(root));
        System.out.println(
                "Migrated "
                        + result.files()
                        + " files / "
                        + result.bytes()
                        + " bytes; source removed="
                        + result.sourceRemoved());
    }

    private static void runBridge(Path root, String[] args) throws Exception {
        int code = BuilderBridge.run(root, args);
        if (code != 0) {
            System.exit(code);
        }
    }

    private static void build(Path root, String[] args) throws Exception {
        BuilderOptions options = parse(args);
        AudiobookBuilder builder = new AudiobookBuilder(root);
        if (!options.separate) {
            System.out.println("Built " + builder.build(options));
            return;
        }
        Files.createDirectories(options.output);
        for (Path input : options.inputs) {
            BuilderOptions one = copy(options);
            one.inputs.clear();
            one.inputs.add(input);
            one.output =
                    options.output.resolve(stripExtension(input.getFileName().toString()) + ".opbook");
            one.title = null;
            System.out.println("Built " + builder.build(one));
        }
    }
    private static BuilderOptions parse(String[] args) {
        BuilderOptions result = new BuilderOptions();
        for (int index = 1; index < args.length; index++) {
            int consumed = applyCoreOption(result, args, index);
            if (consumed < 0) {
                consumed = applyToolOption(result, args, index);
            }
            if (consumed >= 0) {
                index = consumed;
            } else if (!applyFlagOption(result, args[index])) {
                throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        validateTts(result);
        return result;
    }

    private static int applyCoreOption(BuilderOptions result, String[] args, int index) {
        String key = args[index];
        String value;
        switch (key) {
            case "--input":
                value = require(args, index + 1, key);
                result.inputs.add(Path.of(value));
                return index + 1;
            case "--output":
                result.output = Path.of(require(args, index + 1, key));
                return index + 1;
            case "--title":
                result.title = require(args, index + 1, key);
                return index + 1;
            case "--author":
                result.author = require(args, index + 1, key);
                return index + 1;
            case "--language":
                result.language = require(args, index + 1, key);
                return index + 1;
            case "--tts":
                result.tts = require(args, index + 1, key);
                return index + 1;
            case "--voice":
                result.voice = require(args, index + 1, key);
                return index + 1;
            case "--rate":
                result.rate = Double.parseDouble(require(args, index + 1, key));
                return index + 1;
            case "--pause-ms":
                result.pauseMs = Integer.parseInt(require(args, index + 1, key));
                return index + 1;
            default:
                return -1;
        }
    }

    private static int applyToolOption(BuilderOptions result, String[] args, int index) {
        String key = args[index];
        switch (key) {
            case "--ffmpeg":
                result.ffmpeg = Path.of(require(args, index + 1, key));
                return index + 1;
            case "--melo-python":
                result.meloPython = Path.of(require(args, index + 1, key));
                return index + 1;
            case "--melo-workers":
                result.meloWorkers = Integer.parseInt(require(args, index + 1, key));
                return index + 1;
            case "--edge-workers":
                result.edgeWorkers = Integer.parseInt(require(args, index + 1, key));
                return index + 1;
            case "--sherpa-workers":
                result.sherpaWorkers = Integer.parseInt(require(args, index + 1, key));
                return index + 1;
            case "--windows-workers":
                result.windowsWorkers = Integer.parseInt(require(args, index + 1, key));
                return index + 1;
            case "--cover":
                result.cover = Path.of(require(args, index + 1, key));
                return index + 1;
            case "--created-at":
                result.createdAt = Instant.parse(require(args, index + 1, key));
                return index + 1;
            default:
                return -1;
        }
    }

    private static boolean applyFlagOption(BuilderOptions result, String key) {
        if ("--separate".equals(key)) {
            result.separate = true;
            return true;
        }
        if ("--allow-online-tts".equals(key)) {
            result.allowOnlineTts = true;
            return true;
        }
        return false;
    }

    private static void validateTts(BuilderOptions result) {
        if (!TtsProviderCatalog.supports(result.tts)) {
            throw new IllegalArgumentException("Unsupported --tts provider: " + result.tts);
        }
        if (TtsProviderCatalog.entry(result.tts).online() && !result.allowOnlineTts) {
            throw new IllegalArgumentException(
                    "Online TTS sends text to an external service; add --allow-online-tts after"
                            + " reviewing this privacy impact");
        }
    }
    private static BuilderOptions copy(BuilderOptions source) {
        BuilderOptions target = new BuilderOptions();
        target.inputs.addAll(source.inputs);
        target.output = source.output;
        target.title = source.title;
        target.author = source.author;
        target.language = source.language;
        target.tts = source.tts;
        target.voice = source.voice;
        target.rate = source.rate;
        target.pauseMs = source.pauseMs;
        target.maxTrackMinutes = source.maxTrackMinutes;
        target.ffmpeg = source.ffmpeg;
        target.meloPython = source.meloPython;
        target.meloWorkers = source.meloWorkers;
        target.edgeWorkers = source.edgeWorkers;
        target.sherpaWorkers = source.sherpaWorkers;
        target.windowsWorkers = source.windowsWorkers;
        target.cover = source.cover;
        target.createdAt = source.createdAt;
        target.allowOnlineTts = source.allowOnlineTts;
        return target;
    }

    private static String require(String[] args, int index, String key) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + key);
        }
        return args[index];
    }

    static Path findProjectRoot() {
        String configured = System.getenv("X3_BUILDER_HOME");
        if (configured != null && !configured.isBlank()) {
            Path home = Path.of(configured).toAbsolutePath();
            if (Files.isDirectory(home.resolve("specification"))
                    && Files.isDirectory(home.resolve("builder"))) {
                return home;
            }
        }
        Path packaged =
                System.getProperty("jpackage.app-path") == null
                        ? null
                        : Path.of(System.getProperty("jpackage.app-path"))
                                .toAbsolutePath()
                                .getParent();
        if (packaged != null
                && Files.isDirectory(packaged.resolve("specification"))
                && Files.isDirectory(packaged.resolve("builder"))) {
            return packaged;
        }
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("specification"))
                    && Files.isDirectory(current.resolve("builder"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Run from inside the X3OfflineReader project");
    }

    private static void listVoices() throws Exception {
        String script =
                "$ProgressPreference='SilentlyContinue';$v=New-Object -ComObject"
                        + " SAPI.SpVoice;$v.GetVoices()|ForEach-Object{$_.GetDescription()}";
        String encoded =
                java.util.Base64.getEncoder()
                        .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process =
                new ProcessBuilder(
                                "powershell.exe",
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-OutputFormat",
                                "Text",
                                "-EncodedCommand",
                                encoded)
                        .redirectErrorStream(true)
                        .start();
        process.getInputStream().transferTo(System.out);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Unable to list SAPI voices");
        }
    }

    private static void usage() {
        System.out.println(
                "听读工坊\n"
                    + "  voices\n"
                    + "  gui\n"
                    + "  migrate-work --from <legacy-directory>\n"
                    + "  build --input <epub|txt> [--input ...] --output <book.opbook> [options]\n"
                    + "Options: --title --author --language --tts"
                    + " melo|sherpa-kokoro|sherpa-aishell3|windows|edge|tone --voice --rate"
                    + " --pause-ms --cover --ffmpeg --melo-python --melo-workers 1|2"
                    + " --sherpa-workers 1..4 --windows-workers 1..12 --edge-workers 1..12"
                    + " --created-at\n"
                    + "         --allow-online-tts (required for edge; book text is sent to the"
                    + " online service)\n"
                    + "         --separate (treat --output as a directory and export each input"
                    + " separately)");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
