package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.audio.WaveSupport;
import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

/** Windows offline fallback using the built-in SAPI COM voice engine. */
public final class WindowsSapiTtsProvider implements TtsProvider {
    @Override public String id() { return "windows-sapi"; }
    @Override public boolean isAvailable() { return System.getProperty("os.name", "").toLowerCase().contains("windows"); }
    @Override public List<String> voices(){List<String> result=new ArrayList<>();if(!isAvailable())return result;try{String script="$ProgressPreference='SilentlyContinue';[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);(New-Object -ComObject SAPI.SpVoice).GetVoices()|ForEach-Object{$_.GetDescription()}";Process process=new ProcessBuilder(powerShell(),"-NoLogo","-NoProfile","-NonInteractive","-OutputFormat","Text","-Command",script).redirectErrorStream(true).start();for(String line:new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).split("\\R")){String clean=line.trim();if(!clean.isBlank()&&!clean.startsWith("#< CLIXML")&&!clean.startsWith("<Objs")&&(clean.toLowerCase().contains("chinese")||clean.contains("中文")))result.add(clean);}process.waitFor();}catch(Exception ignored){}return result;}

    @Override public SynthesisResult synthesize(SynthesisRequest request, Path workingDirectory) throws Exception {
        if (!isAvailable()) throw new IllegalStateException("Windows SAPI is only available on Windows");
        Files.createDirectories(workingDirectory);
        Path output = workingDirectory.resolve(request.getSegmentId() + ".wav").toAbsolutePath();
        Files.deleteIfExists(output);
        int rate = (int)Math.max(-10, Math.min(10, Math.round((request.getRate() - 1.0) * 10)));
        String script = "$ErrorActionPreference='Stop';"
                + "$v=New-Object -ComObject SAPI.SpVoice;"
                + "$voice=$v.GetVoices()|Where-Object{$_.GetDescription()-eq '" + quote(request.getVoiceId()) + "'}|Select-Object -First 1;"
                + "if($null -eq $voice){throw 'SAPI voice not found'};"
                + "$v.Voice=$voice;$v.Rate=" + rate + ";"
                + "$fs=New-Object -ComObject SAPI.SpFileStream;"
                + "$fs.Open('" + quote(output.toString()) + "',3,$false);"
                + "$v.AudioOutputStream=$fs;[void]$v.Speak('" + quote(request.getText()) + "');$fs.Close();";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder(powerShell(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 46)
            throw new IllegalStateException("Windows SAPI synthesis failed: " + log.strip());
        return new SynthesisResult(output, WaveSupport.durationMs(output));
    }

    private static String quote(String value) { return value.replace("'", "''"); }
    private static String powerShell(){Path x86=Path.of(System.getenv().getOrDefault("WINDIR","C:\\Windows"),"SysWOW64","WindowsPowerShell","v1.0","powershell.exe");return Files.isRegularFile(x86)?x86.toString():"powershell.exe";}
}
