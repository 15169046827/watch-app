package com.canace.x3offlinereader.music;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.text.Normalizer;

/** File-name and metadata rules shared by import and scanning. */
public final class MusicFileInfo {

    public static final class Labels {

        public final String title;

        public final String artist;

        Labels(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }
    }

    private MusicFileInfo() { }

    public static String safeFileName(String raw) {
        String leaf = new File(raw == null ? "" : raw).getName();
        String value = trimTrailingDots(sanitize(leaf).trim());
        if (invalidLeaf(value)) {
            return "audio";
        }
        return shorten(value);
    }

    private static String sanitize(String leaf) {
        String normalized = Normalizer.normalize(leaf, Normalizer.Form.NFC);
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || "/\\:*?\"<>|".indexOf(codePoint) >= 0) {
                clean.append('_');
            } else {
                clean.appendCodePoint(codePoint);
            }
        }
        return clean.toString();
    }

    private static String trimTrailingDots(String value) {
        String result = value;
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean invalidLeaf(String value) {
        return value.isEmpty() || ".".equals(value) || "..".equals(value);
    }

    private static String shorten(String value) {
        if (value.length() <= 180) {
            return value;
        }
        int dot = value.lastIndexOf('.');
        String extension = dot > 0 && value.length() - dot <= 10 ? value.substring(dot) : "";
        int keep = Math.max(1, 180 - extension.length());
        return value.substring(0, keep) + extension;
    }
    public static String stem(String name) {
        String safe = safeFileName(name);
        int dot = safe.lastIndexOf('.');
        return dot > 0 ? safe.substring(0, dot) : safe;
    }

    public static Labels labels(String fileName, String metadataTitle, String metadataArtist) {
        String base = stem(fileName).replaceFirst("^\\d{10,}-", "");
        int split = base.indexOf('-');
        String fileArtist = split > 0 ? base.substring(0, split).trim() : null;
        String fileTitle =
                split > 0 && split + 1 < base.length() ? base.substring(split + 1).trim() : base;
        String title = cleanMetadata(metadataTitle);
        String artist = cleanMetadata(metadataArtist);
        if (title == null) {
            title = fileTitle.isEmpty() ? base : fileTitle;
        }
        if (artist == null) {
            artist = fileArtist;
        }
        return new Labels(title, artist);
    }

    public static String cleanMetadata(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        if (clean.isEmpty() || clean.indexOf('\uFFFD') >= 0) {
            return null;
        }
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) {
                return null;
            }
        }
        return clean;
    }

    public static int displayQuality(File file) {
        String name = file.getName();
        int score = name.matches("^\\d{10,}-.*") ? -100 : 0;
        int underscores = 0;
        int letters = 0;
        int nonAscii = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                underscores++;
            }
            if (Character.isLetterOrDigit(c)) {
                letters++;
            }
            if (c > 127) {
                nonAscii++;
            }
        }
        return score + nonAscii * 4 + letters - underscores * 3;
    }

    public static boolean sameContent(File first, File second) {
        if (!first.isFile() || !second.isFile() || first.length() != second.length()) {
            return false;
        }
        try {
            return MessageDigest.isEqual(sha256(first), sha256(second));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isReadable(File file) {
        if (!file.isFile()) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            return file.length() == 0 || in.read() != -1;
        } catch (Exception ignored) {
            return false;
        }
    }

    static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
        }
        return digest.digest();
    }
}
