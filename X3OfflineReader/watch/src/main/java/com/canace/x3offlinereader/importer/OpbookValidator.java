package com.canace.x3offlinereader.importer;

import com.canace.x3offlinereader.data.LibraryModels;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;

final class OpbookValidator {

    LibraryModels.Book validate(File root) throws Exception {
        JSONObject manifest = json(root, "manifest.json");
        require(
                "opbook".equals(manifest.getString("format"))
                        && "1.0".equals(manifest.getString("formatVersion")),
                "unsupported format");
        LibraryModels.Book book = book(root, manifest.getJSONObject("book"));
        Set<String> ids = new HashSet<>();
        require(ids.add(book.id), "duplicate id");
        Set<String> referenced = new HashSet<>();
        referenced.add("manifest.json");
        referenced.add("checksums.json");
        require("checksums.json".equals(manifest.getString("checksumsPath")), "checksums path");
        validateCover(root, manifest.getJSONObject("book"), referenced);
        validateVolumes(root, manifest.getJSONArray("volumes"), book, ids, referenced);
        rejectUnreferencedManagedFiles(root, referenced);
        return book;
    }

    private static LibraryModels.Book book(File root, JSONObject source) throws Exception {
        LibraryModels.Book book = new LibraryModels.Book();
        book.id = id(source.getString("bookId"));
        book.title = nonempty(source.getString("title"));
        book.author = source.getJSONArray("authors").optString(0, "");
        book.rootPath = root.getAbsolutePath();
        return book;
    }

    private static void validateCover(File root, JSONObject book, Set<String> referenced)
            throws Exception {
        if (!book.has("coverPath")) {
            return;
        }
        String cover = safe(book.getString("coverPath"));
        require("cover.webp".equals(cover), "cover path");
        File file = new File(root, cover);
        require(file.isFile() && file.length() <= 8L * 1024 * 1024, "cover");
        validateWebp(file);
        referenced.add(cover);
    }

    private static void validateVolumes(
            File root,
            JSONArray sources,
            LibraryModels.Book book,
            Set<String> ids,
            Set<String> referenced)
            throws Exception {
        require(sources.length() > 0, "no volumes");
        for (int index = 0; index < sources.length(); index++) {
            book.volumes.add(volume(root, sources.getJSONObject(index), index, ids, referenced));
        }
    }

    private static LibraryModels.Volume volume(
            File root,
            JSONObject source,
            int order,
            Set<String> ids,
            Set<String> referenced)
            throws Exception {
        require(source.getInt("order") == order, "volume order");
        LibraryModels.Volume volume = new LibraryModels.Volume();
        volume.id = id(source.getString("volumeId"));
        require(ids.add(volume.id), "duplicate id");
        volume.title = nonempty(source.getString("title"));
        volume.order = order;
        JSONArray chapters = source.getJSONArray("chapters");
        require(chapters.length() > 0, "no chapters");
        for (int index = 0; index < chapters.length(); index++) {
            volume.chapters.add(
                    chapter(root, chapters.getJSONObject(index), index, ids, referenced));
        }
        return volume;
    }

    private static LibraryModels.Chapter chapter(
            File root,
            JSONObject source,
            int order,
            Set<String> ids,
            Set<String> referenced)
            throws Exception {
        require(source.getInt("order") == order, "chapter order");
        LibraryModels.Chapter chapter = new LibraryModels.Chapter();
        chapter.id = id(source.getString("chapterId"));
        require(ids.add(chapter.id), "duplicate id");
        chapter.title = nonempty(source.getString("title"));
        chapter.order = order;
        chapter.contentPath = resource(source.getString("contentPath"), "content", "json");
        referenced.add(chapter.contentPath);
        Content content = content(root, chapter);
        if (content.title != null) {
            chapter.title = content.title;
        }
        JSONArray tracks = source.getJSONArray("tracks");
        require(tracks.length() > 0, "no tracks");
        for (int index = 0; index < tracks.length(); index++) {
            chapter.tracks.add(
                    track(
                            root,
                            tracks.getJSONObject(index),
                            index,
                            chapter.id,
                            content.blocks,
                            ids,
                            referenced));
        }
        return chapter;
    }

    private static Content content(File root, LibraryModels.Chapter chapter) throws Exception {
        JSONObject source = json(root, chapter.contentPath);
        require(
                "1.0".equals(source.getString("formatVersion"))
                        && chapter.id.equals(source.getString("chapterId")),
                "content identity");
        JSONObject blocks = new JSONObject();
        String displayTitle = null;
        String fallbackTitle = null;
        JSONArray sources = source.getJSONArray("blocks");
        for (int index = 0; index < sources.length(); index++) {
            JSONObject block = sources.getJSONObject(index);
            require(block.getInt("order") == index, "block order");
            String blockId = id(block.getString("blockId"));
            require(!blocks.has(blockId), "duplicate block");
            String blockText = block.getString("text");
            blocks.put(blockId, blockText);
            String normalized = blockText.trim().replaceAll("\\s+", " ");
            if (fallbackTitle == null && !normalized.isEmpty()) {
                fallbackTitle = normalized;
            }
            if (displayTitle == null
                    && "paragraph".equals(block.optString("type"))
                    && !normalized.isEmpty()) {
                displayTitle = normalized;
            }
        }
        return new Content(blocks, displayTitle == null ? fallbackTitle : displayTitle);
    }

    private static LibraryModels.Track track(
            File root,
            JSONObject source,
            int order,
            String chapterId,
            JSONObject blocks,
            Set<String> ids,
            Set<String> referenced)
            throws Exception {
        require(source.getInt("order") == order, "track order");
        LibraryModels.Track track = new LibraryModels.Track();
        track.id = id(source.getString("trackId"));
        require(ids.add(track.id), "duplicate id");
        track.order = order;
        track.durationMs = source.getLong("durationMs");
        require(track.durationMs > 0, "duration");
        require("audio/mp4".equals(source.getString("mimeType")), "mime");
        track.audioPath = resource(source.getString("audioPath"), "audio", "m4a");
        track.timelinePath = resource(source.getString("timelinePath"), "timeline", "json");
        referenced.add(track.audioPath);
        referenced.add(track.timelinePath);
        File audio = new File(root, track.audioPath);
        require(audio.isFile(), "missing audio");
        validateM4a(audio);
        JSONObject timeline = json(root, track.timelinePath);
        requireTimelineIdentity(timeline, track, chapterId);
        validateSegments(timeline.getJSONArray("segments"), blocks, track.durationMs);
        return track;
    }

    private static void requireTimelineIdentity(
            JSONObject timeline, LibraryModels.Track track, String chapterId) throws Exception {
        require(
                "1.0".equals(timeline.getString("formatVersion"))
                        && track.id.equals(timeline.getString("trackId"))
                        && chapterId.equals(timeline.getString("chapterId")),
                "timeline identity");
        require(track.durationMs == timeline.getLong("durationMs"), "timeline duration");
    }

    private static final class Content {

        final JSONObject blocks;

        final String title;

        Content(JSONObject blocks, String title) {
            this.blocks = blocks;
            this.title = title;
        }
    }
    private static void rejectUnreferencedManagedFiles(File root, Set<String> referenced)
            throws Exception {
        try (Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            paths.filter(Files::isRegularFile)
                    .forEach(
                            path -> {
                                String relative =
                                        root.toPath()
                                                .relativize(path)
                                                .toString()
                                                .replace('\\', '/');
                                if ((relative.startsWith("content/")
                                                || relative.startsWith("timeline/")
                                                || relative.startsWith("audio/"))
                                        && !referenced.contains(relative)) {
                                    throw new IllegalArgumentException("unreferenced resource");
                                }
                            });
        }
    }

    private static void validateSegments(JSONArray segments, JSONObject blocks, long duration)
            throws Exception {
        long prior = 0;
        Set<String> ids = new HashSet<>();
        require(segments.length() > 0, "no segments");
        for (int i = 0; i < segments.length(); i++) {
            JSONObject s = segments.getJSONObject(i);
            require(ids.add(id(s.getString("segmentId"))), "duplicate segment");
            long start = s.getLong("startMs");
            long end = s.getLong("endMs");
            require(prior <= start && start < end && end <= duration, "timeline range");
            prior = end;
            String text = blocks.getString(s.getString("blockId"));
            int a = s.getInt("startChar");
            int b = s.getInt("endChar");
            int count = text.codePointCount(0, text.length());
            require(0 <= a && a < b && b <= count, "text range");
            require(textMatches(text, a, b, s.getString("text")), "text mismatch");
        }
    }

    static boolean textMatches(String block, int start, int end, String supplied) {
        int from = block.offsetByCodePoints(0, start);
        int to = block.offsetByCodePoints(0, end);
        String slice = block.substring(from, to);
        if (slice.equals(supplied) || slice.trim().equals(supplied)) {
            return true;
        }
        int candidate = to - supplied.length();
        if (candidate < 0
                || candidate >= from
                || !block.regionMatches(candidate, supplied, 0, supplied.length())) {
            return false;
        }
        String skipped = block.substring(candidate, from);
        if (skipped.isEmpty()) {
            return false;
        }
        for (int offset = 0; offset < skipped.length(); ) {
            int cp = skipped.codePointAt(offset);
            if (cp <= 0x20 || !Character.isWhitespace(cp)) {
                return false;
            }
            offset += Character.charCount(cp);
        }
        return true;
    }

    private static void validateM4a(File file) throws Exception {
        byte[] h = new byte[12];
        try (FileInputStream in = new FileInputStream(file)) {
            require(
                    in.read(h) == 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p',
                    "invalid m4a");
        }
    }

    private static void validateWebp(File file) throws Exception {
        byte[] h = new byte[12];
        try (FileInputStream in = new FileInputStream(file)) {
            require(
                    in.read(h) == 12
                            && h[0] == 'R'
                            && h[1] == 'I'
                            && h[2] == 'F'
                            && h[3] == 'F'
                            && h[8] == 'W'
                            && h[9] == 'E'
                            && h[10] == 'B'
                            && h[11] == 'P',
                    "invalid webp");
        }
    }

    private static JSONObject json(File root, String relative) throws Exception {
        File file = new File(root, safe(relative));
        require(file.isFile() && file.length() <= 16L * 1024 * 1024, "missing/large json");
        return new JSONObject(
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static String safe(String value) {
        require(
                value != null
                        && !value.isEmpty()
                        && !value.startsWith("/")
                        && !value.contains("\\")
                        && !value.contains("\0")
                        && !value.contains("//")
                        && !value.contains(":"),
                "unsafe path");
        for (String p : value.split("/", -1)) {
            require(!p.isEmpty() && !p.equals(".") && !p.equals(".."), "unsafe path");
        }
        return value;
    }

    private static String resource(String value, String directory, String extension) {
        String safe = safe(value);
        require(
                safe.matches(directory + "/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\." + extension),
                "invalid resource path");
        return safe;
    }

    private static String id(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "invalid id");
        return value;
    }

    private static String nonempty(String value) {
        require(value != null && !value.trim().isEmpty(), "empty text");
        return value;
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalArgumentException(message);
        }
    }
}
