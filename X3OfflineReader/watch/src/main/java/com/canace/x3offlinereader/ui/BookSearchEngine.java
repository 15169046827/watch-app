package com.canace.x3offlinereader.ui;

import com.canace.x3offlinereader.data.LibraryModels;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded exact-text search over already validated local content and sentence timelines. */
final class BookSearchEngine {

    static final int MAX_QUERY_CODE_POINTS = 64;

    static final int MAX_RESULTS = 100;

    static final class Outcome {

        final List<Result> results;

        final boolean truncated;

        Outcome(List<Result> results, boolean truncated) {
            this.results = results;
            this.truncated = truncated;
        }
    }

    static final class Result {

        final String chapterId;

        final String chapterTitle;

        final String trackId;

        final String snippet;

        final long trackPositionMs;

        final long chapterPositionMs;

        Result(
                String chapterId,
                String chapterTitle,
                String trackId,
                long trackPositionMs,
                long chapterPositionMs,
                String snippet) {
            this.chapterId = chapterId;
            this.chapterTitle = chapterTitle;
            this.trackId = trackId;
            this.trackPositionMs = trackPositionMs;
            this.chapterPositionMs = chapterPositionMs;
            this.snippet = snippet;
        }

        @Override
        public String toString() {
            return chapterTitle + " · " + format(chapterPositionMs) + "\n" + snippet;
        }
    }

    private static final class Block {

        final int base;

        final String text;

        Block(int base, String text) {
            this.base = base;
            this.text = text;
        }
    }

    private static final class Content {

        final String text;

        final Map<String, Block> blocks;

        Content(String text, Map<String, Block> blocks) {
            this.text = text;
            this.blocks = blocks;
        }
    }

    private static final class Position {

        final int start;

        final int end;

        final String trackId;

        final long trackPositionMs;

        final long chapterPositionMs;

        Position(int start, int end, String trackId, long trackPositionMs, long chapterPositionMs) {
            this.start = start;
            this.end = end;
            this.trackId = trackId;
            this.trackPositionMs = trackPositionMs;
            this.chapterPositionMs = chapterPositionMs;
        }
    }

    private BookSearchEngine() { }

    static Outcome search(List<LibraryModels.ChapterSummary> chapters, String rawQuery)
            throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("empty query");
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("query too long");
        }
        List<Result> results = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (LibraryModels.ChapterSummary chapter : chapters) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            Content content = content(chapter.contentPath);
            List<Position> positions = positions(chapter, content.blocks);
            for (int from = 0; ; ) {
                int hit = indexOf(content.text, query, from);
                if (hit < 0) {
                    break;
                }
                int end = hit + query.length();
                Position position = position(positions, hit, end);
                if (position != null
                        && emitted.add(position.trackId + ":" + position.trackPositionMs)) {
                    if (results.size() >= MAX_RESULTS) {
                        return new Outcome(results, true);
                    }
                    results.add(
                            new Result(
                                    chapter.id,
                                    chapter.title,
                                    position.trackId,
                                    position.trackPositionMs,
                                    position.chapterPositionMs,
                                    snippet(content.text, hit, end)));
                }
                from = hit + Math.max(1, query.length());
            }
        }
        return new Outcome(results, false);
    }

    private static Content content(String path) throws Exception {
        JSONObject root = read(path);
        JSONArray blocks = root.getJSONArray("blocks");
        StringBuilder full = new StringBuilder();
        Map<String, Block> map = new HashMap<>();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (full.length() > 0) {
                full.append("\n\n");
            }
            String value = block.getString("text");
            map.put(block.getString("blockId"), new Block(full.length(), value));
            full.append(value);
        }
        return new Content(full.toString(), map);
    }

    private static List<Position> positions(
            LibraryModels.ChapterSummary chapter, Map<String, Block> blocks) throws Exception {
        List<Position> positions = new ArrayList<>();
        for (LibraryModels.TrackSummary track : chapter.tracks) {
            JSONArray segments = read(track.timelinePath).getJSONArray("segments");
            for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.getJSONObject(i);
                Block block = blocks.get(segment.getString("blockId"));
                if (block == null) {
                    continue;
                }
                int start = block.base + codePointOffset(block.text, segment.getInt("startChar"));
                int end = block.base + codePointOffset(block.text, segment.getInt("endChar"));
                long trackMs = segment.getLong("startMs");
                positions.add(
                        new Position(
                                start, end, track.id, trackMs, track.chapterOffsetMs + trackMs));
            }
        }
        return positions;
    }

    private static Position position(List<Position> positions, int start, int end) {
        for (Position position : positions) {
            if (position.end > start && position.start < end) {
                return position;
            }
        }
        return null;
    }

    static int indexOf(String text, String query, int from) {
        int last = text.length() - query.length();
        outer:
        for (int i = Math.max(0, from); i <= last; i++) {
            for (int j = 0; j < query.length(); j++) {
                char a = text.charAt(i + j);
                char b = query.charAt(j);
                if (a == b) {
                    continue;
                }
                if (asciiLower(a) != asciiLower(b)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static char asciiLower(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }

    static String snippet(String text, int hit, int end) {
        int before = Math.min(28, text.codePointCount(0, hit));
        int after = Math.min(36, text.codePointCount(end, text.length()));
        int start = text.offsetByCodePoints(hit, -before);
        int finish = text.offsetByCodePoints(end, after);
        String value = text.substring(start, finish).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + value + (finish < text.length() ? "…" : "");
    }

    private static int codePointOffset(String value, int count) {
        return value.offsetByCodePoints(
                0, Math.max(0, Math.min(count, value.codePointCount(0, value.length()))));
    }

    private static JSONObject read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() || file.length() > 16L * 1024 * 1024) {
            throw new IllegalArgumentException("invalid search index");
        }
        return new JSONObject(
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        if (total >= 3600) {
            return String.format(
                    Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }
}
