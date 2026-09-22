package com.canace.x3offlinereader.builder.format;

import com.canace.x3offlinereader.builder.model.BookModel;
import com.canace.x3offlinereader.builder.text.SentenceSegmenter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OpbookWriter {
    public static final class TimedSegment {
        public SentenceSegmenter.Segment segment;
        public long startMs;
        public long endMs;
    }

    public static final class TrackArtifact {
        public String trackId;
        public BookModel.Chapter chapter;
        public Path m4a;
        public long durationMs;
        public final List<TimedSegment> segments = new ArrayList<>();
    }

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(
            BookModel.Book book,
            List<TrackArtifact> tracks,
            Path cover,
            Path output,
            Path staging,
            Instant createdAt,
            String encoderVersion)
            throws Exception {
        recreateDirectory(staging);
        Files.createDirectories(staging.resolve("content"));
        Files.createDirectories(staging.resolve("timeline"));
        Files.createDirectories(staging.resolve("audio"));
        Files.copy(cover, staging.resolve("cover.webp"));
        Map<String, List<TrackArtifact>> byChapter = new LinkedHashMap<>();
        for (TrackArtifact track : tracks) {
            byChapter.computeIfAbsent(track.chapter.chapterId, key -> new ArrayList<>()).add(track);
        }

        for (TrackArtifact track : tracks) {
            writeTimeline(track, staging.resolve("timeline/" + track.trackId + ".json"));
            Files.copy(track.m4a, staging.resolve("audio/" + track.trackId + ".m4a"));
        }
        for (BookModel.Volume volume : book.volumes) {
            for (BookModel.Chapter chapter : volume.chapters) {
                writeContent(chapter, staging.resolve("content/" + chapter.chapterId + ".json"));
            }
        }
        writeManifest(book, byChapter, staging.resolve("manifest.json"), createdAt, encoderVersion);
        writeChecksums(staging);
        zip(staging, output);
    }

    private void writeManifest(
            BookModel.Book book,
            Map<String, List<TrackArtifact>> tracks,
            Path path,
            Instant createdAt,
            String encoder)
            throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("format", "opbook");
        root.put("formatVersion", "1.0");
        ObjectNode bookNode = root.putObject("book");
        bookNode.put("bookId", book.bookId);
        bookNode.put("title", book.title);
        bookNode.put("coverPath", "cover.webp");
        ArrayNode authors = bookNode.putArray("authors");
        book.authors.forEach(authors::add);
        bookNode.put("language", book.language);
        ArrayNode volumes = root.putArray("volumes");
        for (BookModel.Volume volume : book.volumes) {
            ObjectNode volumeNode = volumes.addObject();
            volumeNode.put("volumeId", volume.volumeId);
            volumeNode.put("title", volume.title);
            volumeNode.put("order", volume.order);
            ArrayNode chapters = volumeNode.putArray("chapters");
            for (BookModel.Chapter chapter : volume.chapters) {
                ObjectNode chapterNode = chapters.addObject();
                chapterNode.put("chapterId", chapter.chapterId);
                chapterNode.put("title", chapter.title);
                chapterNode.put("order", chapter.order);
                chapterNode.put("contentPath", "content/" + chapter.chapterId + ".json");
                ArrayNode trackNodes = chapterNode.putArray("tracks");
                List<TrackArtifact> chapterTracks = tracks.get(chapter.chapterId);
                for (int i = 0; i < chapterTracks.size(); i++) {
                    TrackArtifact track = chapterTracks.get(i);
                    ObjectNode trackNode = trackNodes.addObject();
                    trackNode.put("trackId", track.trackId);
                    trackNode.put("order", i);
                    trackNode.put("audioPath", "audio/" + track.trackId + ".m4a");
                    trackNode.put("timelinePath", "timeline/" + track.trackId + ".json");
                    trackNode.put("durationMs", track.durationMs);
                    trackNode.put("mimeType", "audio/mp4");
                }
            }
        }
        root.put("checksumsPath", "checksums.json");
        ObjectNode createdWith = root.putObject("createdWith");
        createdWith.put("application", "听读工坊");
        createdWith.put("version", "0.4.1");
        createdWith.put("audioEncoder", encoder);
        root.put("createdAt", createdAt.toString());
        json.writeValue(path.toFile(), root);
    }

    private void writeContent(BookModel.Chapter chapter, Path path) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("formatVersion", "1.0");
        root.put("chapterId", chapter.chapterId);
        ArrayNode blocks = root.putArray("blocks");
        for (BookModel.Block block : chapter.blocks) {
            ObjectNode node = blocks.addObject();
            node.put("blockId", block.blockId);
            node.put("order", block.order);
            node.put("type", block.type);
            node.put("text", block.text);
        }
        json.writeValue(path.toFile(), root);
    }

    private void writeTimeline(TrackArtifact track, Path path) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("formatVersion", "1.0");
        root.put("trackId", track.trackId);
        root.put("chapterId", track.chapter.chapterId);
        root.put("durationMs", track.durationMs);
        ArrayNode segments = root.putArray("segments");
        for (TimedSegment timed : track.segments) {
            SentenceSegmenter.Segment source = timed.segment;
            ObjectNode node = segments.addObject();
            node.put("segmentId", source.segmentId);
            node.put("startMs", timed.startMs);
            node.put("endMs", timed.endMs);
            node.put("text", source.text);
            node.put("blockId", source.blockId);
            node.put("startChar", source.startChar);
            node.put("endChar", source.endChar);
        }
        json.writeValue(path.toFile(), root);
    }

    private void writeChecksums(Path staging) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("formatVersion", "1.0");
        root.put("algorithm", "SHA-256");
        ObjectNode files = root.putObject("files");
        for (Path path : sortedFiles(staging)) {
            String relative = relative(staging, path);
            if (!relative.equals("checksums.json")) {
                files.put(relative, sha256(path));
            }
        }
        json.writeValue(staging.resolve("checksums.json").toFile(), root);
    }

    private static void zip(Path staging, Path output) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.toAbsolutePath().getParent());
        }
        try (OutputStream file = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Path path : sortedFiles(staging)) {
                ZipEntry entry = new ZipEntry(relative(staging, path));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private static List<Path> sortedFiles(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) {
            value.append(String.format("%02x", b));
        }
        return value.toString();
    }

    private static void recreateDirectory(Path path) throws Exception {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(item);
                }
            }
        }
        Files.createDirectories(path);
    }
}
