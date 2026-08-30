package com.canace.x3offlinereader.builder.text;

import com.canace.x3offlinereader.builder.model.BookModel;
import java.util.ArrayList;
import java.util.List;

public final class SentenceSegmenter {
    public static final class Segment {
        public final String segmentId;
        public final String blockId;
        public final int startChar;
        public final int endChar;
        public final String text;

        Segment(String segmentId, String blockId, int startChar, int endChar, String text) {
            this.segmentId = segmentId;
            this.blockId = blockId;
            this.startChar = startChar;
            this.endChar = endChar;
            this.text = text;
        }
    }

    public List<Segment> segment(BookModel.Chapter chapter) {
        List<Segment> result = new ArrayList<>();
        int sequence = 1;
        for (BookModel.Block block : chapter.blocks) {
            String text = block.text;
            int codePointCount = text.codePointCount(0, text.length());
            int start = 0;
            for (int offset = 0; offset < codePointCount; offset++) {
                int charIndex = text.offsetByCodePoints(0, offset);
                int cp = text.codePointAt(charIndex);
                boolean boundary = "。！？!?；;\n".indexOf(cp) >= 0;
                if (boundary) {
                    int end = offset + 1;
                    while (end < codePointCount) {
                        int trailingIndex = text.offsetByCodePoints(0, end);
                        if (!isClosingPunctuation(text.codePointAt(trailingIndex))) break;
                        end++;
                    }
                    sequence = append(result, chapter.chapterId, block.blockId, text, start, end, sequence);
                    start = end;
                    offset = end - 1;
                }
            }
            if (start < codePointCount) append(result, chapter.chapterId, block.blockId, text, start, codePointCount, sequence++);
        }
        return result;
    }

    private int append(List<Segment> output, String chapterId, String blockId, String text,
                       int startCp, int endCp, int sequence) {
        int trimStart = startCp;
        while (trimStart < endCp) {
            int ci = text.offsetByCodePoints(0, trimStart);
            if (!Character.isWhitespace(text.codePointAt(ci))) break;
            trimStart++;
        }
        int trimEnd = endCp;
        while (trimEnd > trimStart) {
            int ci = text.offsetByCodePoints(0, trimEnd - 1);
            if (!Character.isWhitespace(text.codePointAt(ci))) break;
            trimEnd--;
        }
        if (trimStart < trimEnd) {
            int startUtf16 = text.offsetByCodePoints(0, trimStart);
            int endUtf16 = text.offsetByCodePoints(0, trimEnd);
            String slice = text.substring(startUtf16, endUtf16);
            if (!hasSpeakableContent(slice)) return sequence;
            output.add(new Segment(chapterId + "-s" + String.format("%04d", sequence), blockId,
                    trimStart, trimEnd, slice));
            return sequence + 1;
        }
        return sequence;
    }

    private static boolean isClosingPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private static boolean hasSpeakableContent(String text) {
        return text.codePoints().anyMatch(cp -> Character.isLetterOrDigit(cp)
                || Character.isIdeographic(cp)
                || Character.getType(cp) == Character.LETTER_NUMBER
                || Character.getType(cp) == Character.OTHER_NUMBER);
    }
}
