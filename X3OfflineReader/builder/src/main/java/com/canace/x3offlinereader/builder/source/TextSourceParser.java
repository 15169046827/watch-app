package com.canace.x3offlinereader.builder.source;

import com.canace.x3offlinereader.builder.model.BookModel;
import com.canace.x3offlinereader.builder.spi.SourceParser;
import com.canace.x3offlinereader.builder.text.TextNormalizer;
import com.canace.x3offlinereader.builder.util.Ids;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TextSourceParser implements SourceParser {
    private static final Pattern CHAPTER =
            Pattern.compile("^第[0-9零一二三四五六七八九十百千万两〇]+[章节回卷部篇](?:\\s*[^。！？!?；;]{0,40})?$");

    @Override
    public boolean supports(Path source) {
        return source.getFileName().toString().toLowerCase().endsWith(".txt");
    }

    @Override
    public BookModel.Volume parse(Path source, int volumeOrder) throws Exception {
        String normalized = TextNormalizer.normalize(decode(Files.readAllBytes(source)));
        BookModel.Volume volume = new BookModel.Volume();
        volume.volumeId = Ids.indexed("volume", volumeOrder);
        volume.order = volumeOrder;
        volume.title = stripExtension(source.getFileName().toString());

        List<String> chapters = splitChapters(normalized);
        for (int i = 0; i < chapters.size(); i++) {
            String part = chapters.get(i).trim();
            if (part.isEmpty()) {
                continue;
            }
            String[] lines = part.split("\\n", 2);
            String candidate = lines[0].trim();
            boolean heading = CHAPTER.matcher(candidate).matches();
            String title =
                    heading
                            ? candidate
                            : (chapters.size() == 1 ? volume.title : "第" + (i + 1) + "章");
            String body = heading && lines.length > 1 ? lines[1] : part;
            volume.chapters.add(chapter(volumeOrder, volume.chapters.size(), title, body));
        }
        if (volume.chapters.isEmpty()) {
            throw new IllegalArgumentException("No readable text in " + source);
        }
        return volume;
    }

    private static BookModel.Chapter chapter(
            int volumeOrder, int chapterOrder, String title, String body) {
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = Ids.indexed("chapter", volumeOrder, chapterOrder);
        chapter.order = chapterOrder;
        chapter.title = title;
        addBlock(chapter, "heading", title);
        for (String paragraph : body.split("\\n\\s*\\n|\\n")) {
            addBlock(chapter, "paragraph", paragraph.trim());
        }
        return chapter;
    }

    private static void addBlock(BookModel.Chapter chapter, String type, String text) {
        if (text.isBlank()) {
            return;
        }
        BookModel.Block block = new BookModel.Block();
        block.order = chapter.blocks.size();
        block.blockId = chapter.chapterId + "-b" + String.format("%04d", block.order + 1);
        block.type = type;
        block.text = text;
        chapter.blocks.add(block);
    }

    private static List<String> splitChapters(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\n")) {
            if (CHAPTER.matcher(line.trim()).matches() && current.length() > 0) {
                result.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            return Charset.forName("GB18030")
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
