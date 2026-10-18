package com.canace.x3offlinereader.builder.source;

import com.canace.x3offlinereader.builder.model.BookModel;
import com.canace.x3offlinereader.builder.spi.SourceParser;
import com.canace.x3offlinereader.builder.text.TextNormalizer;
import com.canace.x3offlinereader.builder.util.Ids;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.swing.text.html.parser.DTD;
import javax.swing.text.html.parser.Entity;
import javax.swing.text.html.parser.ParserDelegator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Minimal secure EPUB 2/3 spine parser. DRM/encrypted resources are intentionally unsupported. */
public final class EpubSourceParser implements SourceParser {
    private static final int MAX_XML_BYTES = 16 * 1024 * 1024;
    private static final DTD SAFE_HTML_ENTITIES = loadHtmlEntities();
    private static final Pattern EXPLICIT_CHAPTER_TITLE =
            Pattern.compile(
                    "^(?:第\\s*[零〇一二三四五六七八九十百千万萬两兩廿卅壹贰叁肆伍陆柒捌玖拾佰仟0-9]+"
                            + "\\s*[章节回卷部篇]|(?i:chapter)\\s*[-_:#]*\\s*\\d+)"
                            + "(?:\\s+[^。！？!?；;\\n]{0,72})?(?<!\\.)$");
    private static final Pattern OPAQUE_DOCUMENT_TITLE =
            Pattern.compile(
                    "^(?:[0-9a-fA-F]{16,64}|(?=[A-Za-z0-9_-]{6,64}$)"
                            + "(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9_-]+)$");
    private static final Pattern ARABIC_CHAPTER_NUMBER =
            Pattern.compile("^(?:第\\s*|(?i:chapter)\\s*)(\\d+)");

    @Override
    public boolean supports(Path source) {
        return source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    @Override
    public BookModel.Volume parse(Path source, int volumeOrder) throws Exception {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            rejectEncrypted(zip);
            String opfPath = rootFile(zip);
            Document opf = parseEntry(zip, opfPath);
            Map<String, String> manifest = manifest(opf, opfPath);
            Map<String, String> navigationTitles =
                    navigationTitles(zip, opf, manifest);
            List<ParsedDocument> documents =
                    spineDocuments(zip, opf, manifest, navigationTitles);

            BookModel.Volume volume = new BookModel.Volume();
            volume.volumeId = Ids.indexed("volume", volumeOrder);
            volume.order = volumeOrder;
            volume.title = metadata(opf, "title", stripExtension(source.getFileName().toString()));
            if (documents.stream().anyMatch(ParsedDocument::hasExplicitBoundary)) {
                appendExplicitChapters(volume, volumeOrder, documents);
            } else {
                appendSpineChapters(volume, volumeOrder, documents);
            }
            if (volume.chapters.isEmpty()) {
                throw new IllegalArgumentException(
                        "EPUB spine has no readable chapters: " + source);
            }
            return volume;
        }
    }

    private static void rejectEncrypted(ZipFile zip) {
        if (zip.getEntry("META-INF/encryption.xml") != null) {
            throw new IllegalArgumentException("Encrypted/DRM EPUB is not supported");
        }
    }

    private static List<ParsedDocument> spineDocuments(
            ZipFile zip,
            Document opf,
            Map<String, String> manifest,
            Map<String, String> navigationTitles)
            throws Exception {
        List<ParsedDocument> documents = new ArrayList<>();
        NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
        for (int index = 0; index < refs.getLength(); index++) {
            Element ref = (Element) refs.item(index);
            String href = manifest.get(ref.getAttribute("idref"));
            if (href != null) {
                ParsedDocument document =
                        parseDocument(zip, href, navigationTitles.getOrDefault(href, ""));
                if (!document.blocks.isEmpty()) {
                    documents.add(document);
                }
            }
        }
        return documents;
    }

    private static ParsedDocument parseDocument(
            ZipFile zip, String href, String navigationTitle) throws Exception {
        Document xhtml = parseEntry(zip, href);
        Element body = first(xhtml, "body");
        ParsedDocument result = new ParsedDocument();
        result.navigationTitle = TextNormalizer.normalize(navigationTitle);
        result.documentTitle =
                TextNormalizer.normalize(textOfFirst(xhtml.getDocumentElement(), "title"));
        if (body != null) {
            collectBlocks(body, result.blocks);
        }
        return result;
    }

    private static void appendSpineChapters(
            BookModel.Volume volume, int volumeOrder, List<ParsedDocument> documents) {
        for (ParsedDocument document : documents) {
            String fallback = "第" + (volume.chapters.size() + 1) + "章";
            BookModel.Chapter chapter =
                    startChapter(volume, volumeOrder, document.preferredTitle(fallback));
            appendBlocks(chapter, document.blocks, 0, document.blocks.size());
        }
    }

    private static void appendExplicitChapters(
            BookModel.Volume volume, int volumeOrder, List<ParsedDocument> documents) {
        BookModel.Chapter current = null;
        for (ParsedDocument document : documents) {
            List<Integer> boundaries = document.explicitBoundaries();
            if (boundaries.isEmpty()) {
                current = appendUnnumberedDocument(volume, volumeOrder, document, current);
                continue;
            }
            int firstBoundary = boundaries.get(0);
            if (firstBoundary > 0) {
                if (current == null) {
                    current = startChapter(volume, volumeOrder, document.prefaceTitle());
                }
                appendBlocks(current, document.blocks, 0, firstBoundary);
            }
            for (int boundaryIndex = 0; boundaryIndex < boundaries.size(); boundaryIndex++) {
                int start = boundaries.get(boundaryIndex);
                int end =
                        boundaryIndex + 1 < boundaries.size()
                                ? boundaries.get(boundaryIndex + 1)
                                : document.blocks.size();
                ParsedBlock boundary = document.blocks.get(start);
                if (!isRepeatedSplitHeading(current, boundary, start)) {
                    current = startChapter(volume, volumeOrder, boundary.text);
                }
                appendBlocks(current, document.blocks, start + 1, end);
            }
        }
    }

    private static boolean isRepeatedSplitHeading(
            BookModel.Chapter current, ParsedBlock boundary, int index) {
        if (current == null || index != 0 || !boundary.type.equals("heading")) {
            return false;
        }
        var currentNumber = ARABIC_CHAPTER_NUMBER.matcher(current.title);
        var boundaryNumber = ARABIC_CHAPTER_NUMBER.matcher(boundary.text);
        return currentNumber.find()
                && boundaryNumber.find()
                && currentNumber.group(1).equals(boundaryNumber.group(1));
    }

    private static BookModel.Chapter appendUnnumberedDocument(
            BookModel.Volume volume,
            int volumeOrder,
            ParsedDocument document,
            BookModel.Chapter current) {
        if (!document.navigationTitle.isBlank()
                || !document.firstSemanticHeading().isBlank()
                || current == null) {
            String fallback =
                    current == null ? "前言" : "第" + (volume.chapters.size() + 1) + "章";
            BookModel.Chapter next =
                    startChapter(volume, volumeOrder, document.preferredTitle(fallback));
            appendBlocks(next, document.blocks, 0, document.blocks.size());
            return next;
        }
        appendBlocks(current, document.blocks, 0, document.blocks.size());
        return current;
    }

    private static BookModel.Chapter startChapter(
            BookModel.Volume volume, int volumeOrder, String title) {
        int chapterOrder = volume.chapters.size();
        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = Ids.indexed("chapter", volumeOrder, chapterOrder);
        chapter.order = chapterOrder;
        chapter.title = TextNormalizer.normalize(title);
        addBlock(chapter, "heading", chapter.title);
        volume.chapters.add(chapter);
        return chapter;
    }

    private static void collectBlocks(Node node, List<ParsedBlock> blocks) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = elementName(child);
            if (name.matches("p|li|blockquote|h[1-6]")) {
                String text = TextNormalizer.normalize(textContent(child));
                if (!text.isBlank()) {
                    blocks.add(new ParsedBlock(name, blockType(name), text));
                }
            } else if (!name.matches("script|style|nav")) {
                collectBlocks(child, blocks);
            }
        }
    }

    private static String elementName(Node node) {
        return (node.getLocalName() != null ? node.getLocalName() : node.getNodeName())
                .toLowerCase(Locale.ROOT);
    }

    private static String blockType(String elementName) {
        if (elementName.startsWith("h")) {
            return "heading";
        }
        return elementName.equals("blockquote") ? "quote" : "paragraph";
    }

    private static void appendBlocks(
            BookModel.Chapter chapter, List<ParsedBlock> blocks, int start, int end) {
        for (int index = start; index < end; index++) {
            ParsedBlock block = blocks.get(index);
            if (chapter.blocks.size() == 1 && block.text.equals(chapter.title)) {
                continue;
            }
            addBlock(chapter, block.type, block.text);
        }
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

    private static Map<String, String> navigationTitles(
            ZipFile zip, Document opf, Map<String, String> manifest)
            throws Exception {
        Map<String, String> result = new HashMap<>();
        Element spine = first(opf, "spine");
        if (spine != null) {
            String ncxPath = manifest.get(spine.getAttribute("toc"));
            if (ncxPath != null) {
                collectNcxTitles(zip, ncxPath, result);
            }
        }
        NodeList items = opf.getElementsByTagNameNS("*", "item");
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            if (hasToken(item.getAttribute("properties"), "nav")) {
                String navPath = manifest.get(item.getAttribute("id"));
                if (navPath != null) {
                    collectEpub3Titles(zip, navPath, result);
                }
            }
        }
        return result;
    }

    private static boolean hasToken(String value, String token) {
        for (String part : value.trim().split("\\s+")) {
            if (part.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static void collectNcxTitles(
            ZipFile zip, String navigationPath, Map<String, String> result) throws Exception {
        Document ncx = parseEntry(zip, navigationPath);
        NodeList points = ncx.getElementsByTagNameNS("*", "navPoint");
        for (int index = 0; index < points.getLength(); index++) {
            Element point = (Element) points.item(index);
            Element content = firstDescendant(point, "content");
            Element label = firstDescendant(point, "text");
            if (content != null && label != null) {
                putNavigationTitle(
                        result,
                        navigationPath,
                        content.getAttribute("src"),
                        textContent(label));
            }
        }
    }

    private static void collectEpub3Titles(
            ZipFile zip, String navigationPath, Map<String, String> result) throws Exception {
        Document navigation = parseEntry(zip, navigationPath);
        NodeList links = navigation.getElementsByTagNameNS("*", "a");
        for (int index = 0; index < links.getLength(); index++) {
            Element link = (Element) links.item(index);
            putNavigationTitle(
                    result,
                    navigationPath,
                    link.getAttribute("href"),
                    textContent(link));
        }
    }

    private static void putNavigationTitle(
            Map<String, String> result, String navigationPath, String href, String title) {
        String normalizedTitle = TextNormalizer.normalize(title);
        if (!href.isBlank() && !normalizedTitle.isBlank()) {
            String base = directoryOf(navigationPath);
            result.putIfAbsent(resolveHref(base, href), normalizedTitle);
        }
    }

    private static String rootFile(ZipFile zip) throws Exception {
        Document container = parseEntry(zip, "META-INF/container.xml");
        Element rootfile = first(container, "rootfile");
        if (rootfile == null) {
            throw new IllegalArgumentException("EPUB container has no rootfile");
        }
        return safePath(rootfile.getAttribute("full-path"));
    }

    private static Map<String, String> manifest(Document opf, String opfPath) {
        Map<String, String> result = new HashMap<>();
        String base = directoryOf(opfPath);
        NodeList items = opf.getElementsByTagNameNS("*", "item");
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            String href = item.getAttribute("href");
            if (!href.isBlank()) {
                result.put(item.getAttribute("id"), resolveHref(base, href));
            }
        }
        return result;
    }

    private static String directoryOf(String path) {
        return path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
    }

    private static String resolveHref(String base, String href) {
        if (href.startsWith("/") || href.contains("\\") || href.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsafe EPUB href: " + href);
        }
        String withoutFragment = href.split("[#?]", 2)[0];
        URI root = URI.create("/" + base.replace(" ", "%20"));
        URI resolved = root.resolve(withoutFragment.replace(" ", "%20")).normalize();
        String path = resolved.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return safePath(path);
    }

    private static Document parseEntry(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(safePath(path));
        if (entry == null || entry.isDirectory()) {
            throw new IllegalArgumentException("Missing EPUB entry: " + path);
        }
        if (entry.getSize() > MAX_XML_BYTES) {
            throw new IllegalArgumentException("EPUB XML entry too large: " + path);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] data = input.readNBytes(MAX_XML_BYTES + 1);
            if (data.length > MAX_XML_BYTES) {
                throw new IllegalArgumentException("EPUB XML entry too large: " + path);
            }
            return XmlSupport.parse(new ByteArrayInputStream(data));
        }
    }

    private static String safePath(String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains("\0")) {
            throw new IllegalArgumentException("Unsafe EPUB path: " + value);
        }
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Unsafe EPUB path: " + value);
            }
        }
        return value;
    }

    private static Element first(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static Element firstDescendant(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String textOfFirst(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? "" : textContent(nodes.item(0)).trim();
    }

    private static String metadata(Document opf, String localName, String fallback) {
        NodeList nodes = opf.getElementsByTagNameNS("*", localName);
        String value = nodes.getLength() == 0 ? "" : textContent(nodes.item(0)).trim();
        return value.isBlank() ? fallback : value;
    }

    private static String textContent(Node node) {
        StringBuilder result = new StringBuilder();
        appendText(node, result);
        return result.toString();
    }

    private static void appendText(Node node, StringBuilder result) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            result.append(node.getNodeValue());
            return;
        }
        if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
            Entity entity = SAFE_HTML_ENTITIES.getEntity(node.getNodeName());
            result.append(entity == null ? " " : entity.getString());
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            appendText(child, result);
        }
    }

    private static DTD loadHtmlEntities() {
        new ParserDelegator();
        try {
            return DTD.getDTD("html32");
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class ParsedDocument {
        private final List<ParsedBlock> blocks = new ArrayList<>();
        private String navigationTitle = "";
        private String documentTitle = "";

        private boolean hasExplicitBoundary() {
            return blocks.stream().anyMatch(ParsedBlock::isExplicitBoundary);
        }

        private List<Integer> explicitBoundaries() {
            List<Integer> result = new ArrayList<>();
            for (int index = 0; index < blocks.size(); index++) {
                if (blocks.get(index).isExplicitBoundary()) {
                    result.add(index);
                }
            }
            return result;
        }

        private String preferredTitle(String fallback) {
            String heading = firstSemanticHeading();
            if (!heading.isBlank()) {
                return heading;
            }
            if (!navigationTitle.isBlank()) {
                return navigationTitle;
            }
            if (EXPLICIT_CHAPTER_TITLE.matcher(documentTitle).find()
                    || !OPAQUE_DOCUMENT_TITLE.matcher(documentTitle).matches()) {
                return documentTitle.isBlank() ? fallback : documentTitle;
            }
            return fallback;
        }

        private String prefaceTitle() {
            String heading = firstSemanticHeading();
            if (!heading.isBlank()) {
                return heading;
            }
            if (!documentTitle.isBlank()
                    && !OPAQUE_DOCUMENT_TITLE.matcher(documentTitle).matches()) {
                return documentTitle;
            }
            return "前言";
        }

        private String firstSemanticHeading() {
            for (ParsedBlock block : blocks) {
                if (block.elementName.equals("h1")) {
                    return block.text;
                }
            }
            for (ParsedBlock block : blocks) {
                if (block.elementName.equals("h2")) {
                    return block.text;
                }
            }
            return "";
        }
    }

    private static final class ParsedBlock {
        private final String elementName;
        private final String type;
        private final String text;

        private ParsedBlock(String elementName, String type, String text) {
            this.elementName = elementName;
            this.type = type;
            this.text = text;
        }

        private boolean isExplicitBoundary() {
            return text.length() <= 96
                    && !type.equals("quote")
                    && EXPLICIT_CHAPTER_TITLE.matcher(text).matches();
        }
    }
}
