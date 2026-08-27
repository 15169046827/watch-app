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
                    sequence = append(result, chapter.chapterId, block.blockId, text, start, offset + 1, sequence);
                    start = offset + 1;
                }
            }
            if (start < codePointCount) append(result, chapter.chapterId, block.blockId, text, start, codePointCount, sequence++);
        }
        return result;
    }

    private int append(List<Segment> output, String chapterId, String blockId, String text,
                       int startCp, int endCp, int sequence) {
        int startUtf16 = text.offsetByCodePoints(0, startCp);
        int endUtf16 = text.offsetByCodePoints(0, endCp);
        String slice = text.substring(startUtf16, endUtf16).trim();
        if (!slice.isEmpty()) {
            int trimStart = 0;
            while (trimStart < endCp - startCp) {
                int ci = text.offsetByCodePoints(0, startCp + trimStart);
                if (!Character.isWhitespace(text.codePointAt(ci))) break;
                trimStart++;
            }
            output.add(new Segment(chapterId + "-s" + String.format("%04d", sequence), blockId,
                    startCp + trimStart, endCp, slice));
            return sequence + 1;
        }
        return sequence;
    }
}
