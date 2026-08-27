package com.canace.x3offlinereader.builder.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.io.InputStream;

public final class Ids {
    private Ids() {}

    public static String indexed(String prefix, int... indexes) {
        StringBuilder value = new StringBuilder(prefix);
        for (int index : indexes) value.append('-').append(String.format("%03d", index + 1));
        return value.toString();
    }

    public static String bookId(String title, String fingerprint) {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "book";
        return slug.substring(0, Math.min(slug.length(), 80)) + "-" + sha256(fingerprint).substring(0, 12);
    }

    public static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)if(n>0)digest.update(buffer,0,n);}
        StringBuilder result=new StringBuilder(64);for(byte value:digest.digest())result.append(String.format("%02x",value));return result.toString();
    }
}
