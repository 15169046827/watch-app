package com.canace.x3offlinereader.music;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcReader {

    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    private static final Pattern OFFSET =
            Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    private static final class Item {

        final long ms;

        final String text;

        Item(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    public static final class Window {

        public final String previous;

        public final String current;

        public final String next;

        Window(String previous, String current, String next) {
            this.previous = previous;
            this.current = current;
            this.next = next;
        }
    }

    private final List<Item> items = new ArrayList<>();

    private final List<Item> displayItems = new ArrayList<>();

    private String fullText = "";

    public LrcReader(String path) {
        if (path == null) {
            return;
        }
        try {
            load(path);
        } catch (Exception ignored) {
        }
    }

    private void load(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() || file.length() > 8L * 1024 * 1024) {
            return;
        }
        String content = decode(Files.readAllBytes(file.toPath()));
        long offset = parseOffset(content);
        for (String line : content.split("\\r\\n|\\n|\\r")) {
            parseLine(line, offset);
        }
        items.sort(Comparator.comparingLong(item -> item.ms));
        buildDisplay();
    }

    private static long parseOffset(String content) {
        Matcher matcher = OFFSET.matcher(content);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private void parseLine(String line, long offset) {
        Matcher matcher = TIME.matcher(line);
        List<Long> times = new ArrayList<>();
        int textStart = -1;
        int cursor = 0;
        while (matcher.find()) {
            if (!line.substring(cursor, matcher.start()).trim().isEmpty()) {
                break;
            }
            times.add(timestamp(matcher));
            textStart = matcher.end();
            cursor = matcher.end();
        }
        if (textStart < 0) {
            return;
        }
        String text = TIME.matcher(line.substring(textStart)).replaceAll("").trim();
        if (text.isEmpty()) {
            return;
        }
        for (Long time : times) {
            items.add(new Item(Math.max(0, time + offset), text));
        }
    }

    private static long timestamp(Matcher matcher) {
        String fractionText = matcher.group(3);
        long fraction = fractionText == null ? 0 : Long.parseLong(fractionText);
        if (fractionText != null && fractionText.length() == 2) {
            fraction *= 10;
        } else if (fractionText != null && fractionText.length() == 1) {
            fraction *= 100;
        }
        return Long.parseLong(matcher.group(1)) * 60000
                + Long.parseLong(matcher.group(2)) * 1000
                + fraction;
    }
    public String at(long ms) {
        return window(ms).current;
    }

    public Window window(long ms) {
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).ms > ms) {
                break;
            }
            index = i;
        }
        if (index < 0) {
            return new Window("", "", distinctAfter(-1, ""));
        }
        String current = items.get(index).text;
        return new Window(distinctBefore(index, current), current, distinctAfter(index, current));
    }

    public String fullText() {
        return fullText;
    }

    public int activeIndex(long ms) {
        int index = -1;
        for (int i = 0; i < displayItems.size(); i++) {
            if (displayItems.get(i).ms > ms) {
                break;
            }
            index = i;
        }
        return index;
    }

    public int activeStart(int index) {
        if (index < 0 || index >= displayItems.size()) {
            return 0;
        }
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += displayItems.get(i).text.length() + 2;
        }
        return offset;
    }

    public int activeEnd(int index) {
        return index < 0 || index >= displayItems.size()
                ? 0
                : activeStart(index) + displayItems.get(index).text.length();
    }

    private void buildDisplay() {
        StringBuilder text = new StringBuilder();
        for (Item item : items) {
            if (!displayItems.isEmpty()
                    && displayItems.get(displayItems.size() - 1).text.equals(item.text)) {
                continue;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(item.text);
            displayItems.add(item);
        }
        fullText = text.toString();
    }

    private String distinctBefore(int index, String current) {
        for (int i = index - 1; i >= 0; i--) {
            String candidate = items.get(i).text;
            if (!candidate.equals(current)) {
                return candidate;
            }
        }
        return "";
    }

    private String distinctAfter(int index, String current) {
        for (int i = index + 1; i < items.size(); i++) {
            String candidate = items.get(i).text;
            if (!candidate.equals(current)) {
                return candidate;
            }
        }
        return "";
    }

    static String decode(byte[] bytes) throws Exception {
        if (starts(bytes, 0xEF, 0xBB, 0xBF)) {
            return strict(Arrays.copyOfRange(bytes, 3, bytes.length), StandardCharsets.UTF_8);
        }
        if (starts(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (starts(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return strict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ignored) {
            return strict(bytes, Charset.forName("GB18030"));
        }
    }

    private static String strict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
