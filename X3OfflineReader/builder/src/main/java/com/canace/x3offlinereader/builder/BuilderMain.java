package com.canace.x3offlinereader.builder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class BuilderMain {
    private BuilderMain() {}

    public static void main(String[] args) throws Exception {
        // Some jpackage versions inherit the primary GUI launcher's default argument.
        // Keep the additional console launcher usable even if that packaging quirk recurs.
        if(args.length>1&&args[0].equals("gui"))args=Arrays.copyOfRange(args,1,args.length);
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) { usage(); return; }
        if (args[0].equals("voices")) { listVoices(); return; }
        Path root=findProjectRoot();
        if(args[0].equals("migrate-work")){
            if(args.length!=3||!args[1].equals("--from"))throw new IllegalArgumentException("migrate-work requires --from <legacy-directory>");
            BuilderWorkDirectory.MigrationResult result=BuilderWorkDirectory.migrate(Path.of(args[2]),BuilderWorkDirectory.resolve(root));
            System.out.println("Migrated "+result.files()+" files / "+result.bytes()+" bytes; source removed="+result.sourceRemoved());return;
        }
        BuilderWorkDirectory.initialize(root);
        if (args[0].startsWith("bridge-")) { int code=BuilderBridge.run(root,args);if(code!=0)System.exit(code);return; }
        if (args[0].equals("gui")) { javax.swing.SwingUtilities.invokeLater(()->new BuilderGui(root).show()); return; }
        if (!args[0].equals("build")) throw new IllegalArgumentException("Unknown command: " + args[0]);
        BuilderOptions options = parse(args);
        AudiobookBuilder builder = new AudiobookBuilder(root);
        if (options.separate) {
            Files.createDirectories(options.output);
            for (Path input : options.inputs) {
                BuilderOptions one = copy(options); one.inputs.clear(); one.inputs.add(input);
                String name = stripExtension(input.getFileName().toString()) + ".opbook"; one.output = options.output.resolve(name);
                one.title = null;
                System.out.println("Built " + builder.build(one));
            }
        } else {
            System.out.println("Built " + builder.build(options));
        }
    }

    private static BuilderOptions parse(String[] args) {
        BuilderOptions result = new BuilderOptions();
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            switch (key) {
                case "--input" -> result.inputs.add(Path.of(require(args, ++i, key)));
                case "--output" -> result.output = Path.of(require(args, ++i, key));
                case "--title" -> result.title = require(args, ++i, key);
                case "--author" -> result.author = require(args, ++i, key);
                case "--language" -> result.language = require(args, ++i, key);
                case "--tts" -> result.tts = require(args, ++i, key);
                case "--voice" -> result.voice = require(args, ++i, key);
                case "--rate" -> result.rate = Double.parseDouble(require(args, ++i, key));
                case "--pause-ms" -> result.pauseMs = Integer.parseInt(require(args, ++i, key));
                case "--ffmpeg" -> result.ffmpeg = Path.of(require(args, ++i, key));
                case "--melo-python" -> result.meloPython = Path.of(require(args, ++i, key));
                case "--melo-workers" -> result.meloWorkers = Integer.parseInt(require(args, ++i, key));
                case "--edge-workers" -> result.edgeWorkers = Integer.parseInt(require(args, ++i, key));
                case "--sherpa-workers" -> result.sherpaWorkers = Integer.parseInt(require(args, ++i, key));
                case "--windows-workers" -> result.windowsWorkers = Integer.parseInt(require(args, ++i, key));
                case "--cover" -> result.cover = Path.of(require(args, ++i, key));
                case "--created-at" -> result.createdAt = Instant.parse(require(args, ++i, key));
                case "--separate" -> result.separate = true;
                case "--allow-online-tts" -> result.allowOnlineTts = true;
                default -> throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        if (!TtsProviderCatalog.supports(result.tts)) throw new IllegalArgumentException("Unsupported --tts provider: " + result.tts);
        if (TtsProviderCatalog.entry(result.tts).online() && !result.allowOnlineTts)
            throw new IllegalArgumentException("Online TTS sends text to an external service; add --allow-online-tts after reviewing this privacy impact");
        return result;
    }

    private static BuilderOptions copy(BuilderOptions source) {
        BuilderOptions target = new BuilderOptions(); target.inputs.addAll(source.inputs); target.output = source.output; target.title = source.title;
        target.author = source.author; target.language = source.language; target.tts = source.tts; target.voice = source.voice;
        target.rate = source.rate; target.pauseMs = source.pauseMs;target.maxTrackMinutes=source.maxTrackMinutes; target.ffmpeg = source.ffmpeg; target.meloPython = source.meloPython;target.meloWorkers=source.meloWorkers;target.edgeWorkers=source.edgeWorkers;target.sherpaWorkers=source.sherpaWorkers;target.windowsWorkers=source.windowsWorkers; target.cover=source.cover;target.createdAt = source.createdAt;target.allowOnlineTts=source.allowOnlineTts; return target;
    }

    private static String require(String[] args, int index, String key) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + key); return args[index];
    }

    static Path findProjectRoot() {
        String configured=System.getenv("X3_BUILDER_HOME");if(configured!=null&&!configured.isBlank()){Path home=Path.of(configured).toAbsolutePath();if(Files.isDirectory(home.resolve("specification"))&&Files.isDirectory(home.resolve("builder")))return home;}
        Path packaged=System.getProperty("jpackage.app-path") == null?null:Path.of(System.getProperty("jpackage.app-path")).toAbsolutePath().getParent();
        if(packaged!=null&&Files.isDirectory(packaged.resolve("specification"))&&Files.isDirectory(packaged.resolve("builder")))return packaged;
        Path current = Path.of("").toAbsolutePath();
        while (current != null) { if (Files.isDirectory(current.resolve("specification")) && Files.isDirectory(current.resolve("builder"))) return current; current = current.getParent(); }
        throw new IllegalStateException("Run from inside the X3OfflineReader project");
    }

    private static void listVoices() throws Exception {
        String script = "$ProgressPreference='SilentlyContinue';$v=New-Object -ComObject SAPI.SpVoice;$v.GetVoices()|ForEach-Object{$_.GetDescription()}";
        String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand", encoded).redirectErrorStream(true).start();
        process.getInputStream().transferTo(System.out); if (process.waitFor() != 0) throw new IllegalStateException("Unable to list SAPI voices");
    }

    private static void usage() {
        System.out.println("听读工坊\n"
                + "  voices\n"
                + "  gui\n"
                + "  migrate-work --from <legacy-directory>\n"
                + "  build --input <epub|txt> [--input ...] --output <book.opbook> [options]\n"
                + "Options: --title --author --language --tts melo|sherpa-kokoro|sherpa-aishell3|windows|edge|tone --voice --rate --pause-ms --cover --ffmpeg --melo-python --melo-workers 1|2 --sherpa-workers 1..4 --windows-workers 1..12 --edge-workers 1..12 --created-at\n"
                + "         --allow-online-tts (required for edge; book text is sent to the online service)\n"
                + "         --separate (treat --output as a directory and export each input separately)");
    }

    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
}
