package com.canace.x3offlinereader.builder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Stable locations for distributable, public-sentence voice previews. */
final class VoicePreviewLibrary {
    static final double RATE = 1.0;
    static final String REVISION = "natural-zh-v6-text-v2";

    private VoicePreviewLibrary() {}

    static Path path(Path root, String provider, String voice) {
        return root.resolve("builder/voice-previews").resolve(provider).resolve(key(provider, voice) + ".wav");
    }

    static boolean available(Path root, String provider, String voice) {
        try { Path path=path(root,provider,voice);if(!Files.isRegularFile(path)||Files.size(path)<=46)return false;long duration=com.canace.x3offlinereader.builder.audio.WaveSupport.durationMs(path);return duration>=5000&&duration<=10000; }
        catch (Exception ignored) { return false; }
    }

    static List<String> cachedVoices(Path root,String provider){
        Path index=root.resolve("builder/voice-previews/index.tsv");if(!Files.isRegularFile(index))return List.of();
        try{List<String> result=new ArrayList<>();for(String line:Files.readAllLines(index,StandardCharsets.UTF_8)){String[] fields=line.split("\t",6);if(fields.length==6&&fields[0].equals(REVISION)&&fields[1].equals(provider)&&available(root,provider,fields[2]))result.add(fields[2]);}return List.copyOf(result);}catch(Exception ignored){return List.of();}
    }

    static String key(String provider, String voice) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] value=(keyRevision(provider)+"\n"+provider+"\n"+voice+"\n"+sentence(provider)+"\n"+RATE).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(value)).substring(0,24);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String keyRevision(String provider) {
        return provider!=null&&provider.toLowerCase(java.util.Locale.ROOT).startsWith("sherpa-")?REVISION:"natural-zh-v5-provider-length";
    }

    static String sentence(String provider) {
        if ("sherpa-kokoro".equalsIgnoreCase(provider) || "sherpa-aishell3".equalsIgnoreCase(provider)) return "你好，欢迎试听这段自然语音，也愿这个声音陪你享受阅读时光。";
        return "你好，欢迎试听这段自然语音。愿这个声音陪你读过每一段文字，也陪你享受阅读时光。";
    }

    static String adjustedSentence(String provider,long durationMs) {
        if (durationMs > 10000) {
            if ("sherpa-kokoro".equalsIgnoreCase(provider) || "sherpa-aishell3".equalsIgnoreCase(provider)) return "你好，欢迎试听这段自然语音。";
            return "你好，欢迎试听自然语音。";
        } else if (durationMs < 5000 && ("sherpa-kokoro".equalsIgnoreCase(provider) || "sherpa-aishell3".equalsIgnoreCase(provider))) {
            return "你好，欢迎试听这段自然语音。愿这个声音陪你读过每一段文字，也陪你享受阅读时光。";
        }
        return sentence(provider);
    }
}
