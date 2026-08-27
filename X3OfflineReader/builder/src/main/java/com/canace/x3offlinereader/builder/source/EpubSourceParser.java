package com.canace.x3offlinereader.builder.source;

import com.canace.x3offlinereader.builder.model.BookModel;
import com.canace.x3offlinereader.builder.spi.SourceParser;
import com.canace.x3offlinereader.builder.text.TextNormalizer;
import com.canace.x3offlinereader.builder.util.Ids;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import javax.swing.text.html.parser.DTD;
import javax.swing.text.html.parser.Entity;
import javax.swing.text.html.parser.ParserDelegator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Minimal secure EPUB 2/3 spine parser. DRM/encrypted resources are intentionally unsupported. */
public final class EpubSourceParser implements SourceParser {
    private static final DTD SAFE_HTML_ENTITIES = loadHtmlEntities();
    @Override public boolean supports(Path source) { return source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub"); }

    @Override public BookModel.Volume parse(Path source, int volumeOrder) throws Exception {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            if (zip.getEntry("META-INF/encryption.xml") != null) throw new IllegalArgumentException("Encrypted/DRM EPUB is not supported");
            String opfPath = rootFile(zip);
            Document opf = parseEntry(zip, opfPath);
            Map<String, String> manifest = manifest(opf, opfPath);

            BookModel.Volume volume = new BookModel.Volume();
            volume.volumeId = Ids.indexed("volume", volumeOrder);
            volume.order = volumeOrder;
            volume.title = metadata(opf, "title", stripExtension(source.getFileName().toString()));

            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                Element ref = (Element) refs.item(i);
                String href = manifest.get(ref.getAttribute("idref"));
                if (href == null) continue;
                BookModel.Chapter chapter = parseChapter(zip, href, volumeOrder, volume.chapters.size());
                if (chapter != null) volume.chapters.add(chapter);
            }
            if (volume.chapters.isEmpty()) throw new IllegalArgumentException("EPUB spine has no readable chapters: " + source);
            return volume;
        }
    }

    private static BookModel.Chapter parseChapter(ZipFile zip, String href, int volumeOrder, int chapterOrder) throws Exception {
        Document xhtml = parseEntry(zip, href);
        Element body = first(xhtml, "body");
        if (body == null) return null;
        String title = textOfFirst(body, "h1");
        if (title.isBlank()) title = textOfFirst(body, "h2");
        if (title.isBlank()) title = textOfFirst(xhtml.getDocumentElement(), "title");
        if (title.isBlank()) title = "第" + (chapterOrder + 1) + "章";

        BookModel.Chapter chapter = new BookModel.Chapter();
        chapter.chapterId = Ids.indexed("chapter", volumeOrder, chapterOrder);
        chapter.order = chapterOrder;
        chapter.title = TextNormalizer.normalize(title);
        addBlock(chapter, "heading", chapter.title);
        collectBlocks(body, chapter);
        return chapter.blocks.size() > 1 ? chapter : null;
    }

    private static void collectBlocks(Node node, BookModel.Chapter chapter) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getLocalName() != null ? child.getLocalName().toLowerCase() : child.getNodeName().toLowerCase();
            if (name.matches("p|li|blockquote|h[2-6]")) {
                String text = TextNormalizer.normalize(textContent(child));
                addBlock(chapter, name.startsWith("h") ? "heading" : name.equals("blockquote") ? "quote" : "paragraph", text);
            } else if (!name.matches("script|style|nav")) {
                collectBlocks(child, chapter);
            }
        }
    }

    private static void addBlock(BookModel.Chapter chapter, String type, String text) {
        if (text.isBlank() || (type.equals("heading") && text.equals(chapter.title) && !chapter.blocks.isEmpty())) return;
        BookModel.Block block = new BookModel.Block();
        block.order = chapter.blocks.size();
        block.blockId = chapter.chapterId + "-b" + String.format("%04d", block.order + 1);
        block.type = type;
        block.text = text;
        chapter.blocks.add(block);
    }

    private static String rootFile(ZipFile zip) throws Exception {
        Document container = parseEntry(zip, "META-INF/container.xml");
        Element rootfile = first(container, "rootfile");
        if (rootfile == null) throw new IllegalArgumentException("EPUB container has no rootfile");
        return safePath(rootfile.getAttribute("full-path"));
    }

    private static Map<String, String> manifest(Document opf, String opfPath) {
        Map<String, String> result = new HashMap<>();
        String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        NodeList items = opf.getElementsByTagNameNS("*", "item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String href = item.getAttribute("href");
            if (!href.isBlank()) result.put(item.getAttribute("id"), resolveHref(base,href));
        }
        return result;
    }

    private static String resolveHref(String base,String href){
        if(href.startsWith("/")||href.contains("\\")||href.indexOf('\0')>=0)throw new IllegalArgumentException("Unsafe EPUB href: "+href);
        String withoutFragment=href.split("[#?]",2)[0];
        URI root=URI.create("/"+base.replace(" ","%20"));URI resolved=root.resolve(withoutFragment.replace(" ","%20")).normalize();
        String path=resolved.getPath();if(path.startsWith("/"))path=path.substring(1);return safePath(path);
    }

    private static Document parseEntry(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(safePath(path));
        if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("Missing EPUB entry: " + path);
        if (entry.getSize() > 16L * 1024 * 1024) throw new IllegalArgumentException("EPUB XML entry too large: " + path);
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] data=input.readNBytes(16*1024*1024+1);
            if(data.length>16*1024*1024)throw new IllegalArgumentException("EPUB XML entry too large: "+path);
            return XmlSupport.parse(new ByteArrayInputStream(data));
        }
    }

    private static String safePath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains("\0"))
            throw new IllegalArgumentException("Unsafe EPUB path: " + value);
        String[] parts = value.split("/", -1);
        for (String part : parts) if (part.isBlank() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Unsafe EPUB path: " + value);
        return value;
    }

    private static Element first(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
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
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendText(child, result);
    }

    private static DTD loadHtmlEntities() {
        new ParserDelegator();
        try { return DTD.getDTD("html32"); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
}
