package com.canace.x3offlinereader.ui;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class TimelineReader {

    static final class Segment {

        final long start;

        final long end;

        final String text;

        final int displayStart;

        final int displayEnd;

        Segment(long start, long end, String text, int displayStart, int displayEnd) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.displayStart = displayStart;
            this.displayEnd = displayEnd;
        }
    }

    final List<Segment> segments = new ArrayList<>();

    final String fullText;

    TimelineReader(String timelinePath, String contentPath) throws Exception {
        JSONObject content = read(contentPath);
        JSONArray blocks = content.getJSONArray("blocks");
        StringBuilder text = new StringBuilder();
        Map<String, Integer> starts = new HashMap<>();
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (text.length() > 0) {
                text.append("\n\n");
            }
            starts.put(block.getString("blockId"), text.length());
            String value = block.getString("text");
            values.put(block.getString("blockId"), value);
            text.append(value);
        }
        fullText = text.toString();
        JSONObject timeline = read(timelinePath);
        JSONArray items = timeline.getJSONArray("segments");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String blockId = item.getString("blockId");
            String value = values.get(blockId);
            int base = starts.containsKey(blockId) ? starts.get(blockId) : 0;
            int from = codePointOffset(value, item.getInt("startChar"));
            int to = codePointOffset(value, item.getInt("endChar"));
            segments.add(
                    new Segment(
                            item.getLong("startMs"),
                            item.getLong("endMs"),
                            item.getString("text"),
                            base + from,
                            base + to));
        }
    }

    String at(long position) {
        if (segments.isEmpty()) {
            return "";
        }
        int low = 0;
        int high = segments.size() - 1;
        int selected = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).start <= position) {
                selected = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return segments.get(selected).text;
    }

    int activeIndex(long position) {
        if (segments.isEmpty()) {
            return -1;
        }
        int low = 0;
        int high = segments.size() - 1;
        int selected = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).start <= position) {
                selected = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return selected;
    }

    CharSequence highlighted(int index) {
        SpannableString value = new SpannableString(fullText);
        if (index >= 0 && index < segments.size()) {
            Segment s = segments.get(index);
            int flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;
            value.setSpan(
                    new ForegroundColorSpan(Color.rgb(255, 193, 7)),
                    s.displayStart,
                    s.displayEnd,
                    flags);
            value.setSpan(
                    new BackgroundColorSpan(Color.rgb(45, 45, 32)),
                    s.displayStart,
                    s.displayEnd,
                    flags);
            value.setSpan(
                    new StyleSpan(android.graphics.Typeface.BOLD),
                    s.displayStart,
                    s.displayEnd,
                    flags);
        }
        return value;
    }

    int activeStart(int index) {
        return index >= 0 && index < segments.size() ? segments.get(index).displayStart : 0;
    }

    private static int codePointOffset(String value, int count) {
        return value == null
                ? 0
                : value.offsetByCodePoints(
                        0, Math.max(0, Math.min(count, value.codePointCount(0, value.length()))));
    }

    private static JSONObject read(String path) throws Exception {
        return new JSONObject(
                new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8));
    }
}
