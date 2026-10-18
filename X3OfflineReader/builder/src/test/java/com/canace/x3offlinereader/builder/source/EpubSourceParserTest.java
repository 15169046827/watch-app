package com.canace.x3offlinereader.builder.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.canace.x3offlinereader.builder.model.BookModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EpubSourceParserTest {
    @TempDir Path temp;

    @Test
    void parsesSpineAndSpaceInHref() throws Exception {
        Map<String, String> files = base();
        files.put(
                "OPS/Text Chapter.xhtml",
                "<html"
                    + " xmlns='http://www.w3.org/1999/xhtml'><head><title>正文</title></head><body><h1>第一章</h1><p>公开测试内容。</p></body></html>");
        Path epub = zip(files, "ok.epub");
        BookModel.Volume volume = new EpubSourceParser().parse(epub, 0);
        assertEquals("测试书", volume.title);
        assertEquals(1, volume.chapters.size());
        assertEquals("第一章", volume.chapters.get(0).title);
        assertEquals("公开测试内容。", volume.chapters.get(0).blocks.get(1).text);
    }

    @Test
    void parsesCommonXhtmlDoctypeWithoutLoadingExternalDtd() throws Exception {
        Map<String, String> files = base();
        files.put(
                "OPS/Text Chapter.xhtml",
                "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN'"
                    + " 'https://example.invalid/xhtml11.dtd'><html"
                    + " xmlns='http://www.w3.org/1999/xhtml'><head><title>正文</title></head><body><h1>第一章</h1><p>带安全&nbsp;DOCTYPE"
                    + " 的内容。</p></body></html>");
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "doctype.epub"), 0);
        assertEquals(1, volume.chapters.size());
        assertEquals("带安全 DOCTYPE 的内容。", volume.chapters.get(0).blocks.get(1).text);
    }

    @Test
    void rejectsEncryptedEpub() throws Exception {
        Map<String, String> files = base();
        files.put("META-INF/encryption.xml", "<encryption/>");
        assertThrows(
                IllegalArgumentException.class,
                () -> new EpubSourceParser().parse(zip(files, "drm.epub"), 0));
    }

    @Test
    void rejectsInternalDoctypeEntityDeclarations() throws Exception {
        Map<String, String> files = base();
        files.put(
                "OPS/Text Chapter.xhtml",
                "<!DOCTYPE html [<!ENTITY xxe SYSTEM 'file:///C:/Windows/win.ini'>]><html"
                        + " xmlns='http://www.w3.org/1999/xhtml'><body><p>&xxe;</p></body></html>");
        assertThrows(
                IllegalArgumentException.class,
                () -> new EpubSourceParser().parse(zip(files, "xxe.epub"), 0));
    }

    @Test
    void splitsLogicalChaptersAndPreservesSpineContinuation() throws Exception {
        Map<String, String> files = base();
        files.put(
                "OPS/Text Chapter.xhtml",
                html("<p>公开前言。</p><p>第1章 Chapter 1</p><p>第一章公开内容。</p>"));
        files.put(
                "OPS/continuation.xhtml",
                html("<h2>Chapter 1</h2><p>第一章跨文件续文。</p>"
                        + "<p>第2章 Chapter 2</p><p>第二章公开内容。</p>"));
        files.put(
                "OPS/book.opf",
                files.get("OPS/book.opf")
                        .replace(
                                "</manifest>",
                                "<item id='c2' href='continuation.xhtml'"
                                        + " media-type='application/xhtml+xml'/></manifest>")
                        .replace("</spine>", "<itemref idref='c2'/></spine>"));
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "split.epub"), 0);
        assertEquals(3, volume.chapters.size());
        assertEquals("前言", volume.chapters.get(0).title);
        assertEquals("第1章 Chapter 1", volume.chapters.get(1).title);
        assertEquals("第一章跨文件续文。", volume.chapters.get(1).blocks.get(2).text);
        assertEquals("第2章 Chapter 2", volume.chapters.get(2).title);
        assertFalse(volume.chapters.stream().flatMap(chapter -> chapter.blocks.stream())
                .anyMatch(block -> block.text.equals("r7x2q9ab")));
    }

    @Test
    void doesNotSplitProseOrQuotedChapterReferences() throws Exception {
        Map<String, String> files = base();
        files.put(
                "OPS/Text Chapter.xhtml",
                html("<p>第1章 开始</p><p>第一章公开内容。</p>"
                        + "<p>第89章人物解释了此前发生的事情，接着继续交谈。</p>"
                        + "<blockquote>第2章 被引用的标题</blockquote>"
                        + "<p>Chapter 3 describes the next event.</p><p>第2章 继续</p>"));
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "prose.epub"), 0);
        assertEquals(2, volume.chapters.size());
        assertEquals(5, volume.chapters.get(0).blocks.size());
    }

    @Test
    void usesNcxTitleAndDoesNotSpeakOpaqueHtmlTitle() throws Exception {
        Map<String, String> files = base();
        files.put("OPS/Text Chapter.xhtml", html("<p>公开正文。</p>"));
        files.put(
                "OPS/toc.ncx",
                "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint>"
                        + "<navLabel><text>序言</text></navLabel>"
                        + "<content src='Text Chapter.xhtml#start'/></navPoint></navMap></ncx>");
        files.put(
                "OPS/book.opf",
                files.get("OPS/book.opf")
                        .replace("</manifest>",
                                "<item id='ncx' href='toc.ncx'"
                                        + " media-type='application/x-dtbncx+xml'/></manifest>")
                        .replace("<spine>", "<spine toc='ncx'>"));
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "ncx.epub"), 0);
        assertEquals("序言", volume.chapters.get(0).title);
        assertEquals("序言", volume.chapters.get(0).blocks.get(0).text);
        assertEquals("公开正文。", volume.chapters.get(0).blocks.get(1).text);
    }

    @Test
    void includesAllBodyHeadingsOnce() throws Exception {
        Map<String, String> files = base();
        files.put(
                "OPS/Text Chapter.xhtml",
                html("<h1>第一章</h1><p>公开正文。</p><h1>第二章</h1><p>另一段。</p>"));
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "headings.epub"), 0);
        assertEquals(2, volume.chapters.size());
        assertEquals(2, volume.chapters.get(0).blocks.size());
        assertEquals("第二章", volume.chapters.get(1).title);
    }

    @Test
    void usesEpub3NavigationForUnnumberedChapter() throws Exception {
        Map<String, String> files = base();
        files.put("OPS/Text Chapter.xhtml", html("<p>公开正文。</p>"));
        files.put(
                "OPS/nav.xhtml",
                "<html xmlns='http://www.w3.org/1999/xhtml'><body><nav><ol><li>"
                        + "<a href='Text%20Chapter.xhtml#start'>公开序章</a>"
                        + "</li></ol></nav></body></html>");
        files.put(
                "OPS/book.opf",
                files.get("OPS/book.opf")
                        .replace(
                                "</manifest>",
                                "<item id='nav' href='nav.xhtml' properties='nav'"
                                        + " media-type='application/xhtml+xml'/></manifest>"));
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "nav.epub"), 0);
        assertEquals("公开序章", volume.chapters.get(0).title);
        assertEquals(2, volume.chapters.get(0).blocks.size());
    }

    @Test
    void fallsBackSafelyWhenOnlyDocumentTitleIsOpaque() throws Exception {
        Map<String, String> files = base();
        files.put("OPS/Text Chapter.xhtml", html("<p>普通正文。</p>"));
        BookModel.Volume volume = new EpubSourceParser().parse(zip(files, "opaque.epub"), 0);
        assertEquals("第1章", volume.chapters.get(0).title);
        assertEquals("普通正文。", volume.chapters.get(0).blocks.get(1).text);
    }

    private static String html(String body) {
        return "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>r7x2q9ab</title>"
                + "</head><body>" + body + "</body></html>";
    }

    private Map<String, String> base() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "META-INF/container.xml",
                "<container"
                    + " xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile"
                    + " full-path='OPS/book.opf'/></rootfiles></container>");
        files.put(
                "OPS/book.opf",
                "<package xmlns='http://www.idpf.org/2007/opf'><metadata"
                    + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>测试书</dc:title></metadata><manifest><item"
                    + " id='c1' href='Text Chapter.xhtml'"
                    + " media-type='application/xhtml+xml'/></manifest><spine><itemref"
                    + " idref='c1'/></spine></package>");
        return files;
    }

    private Path zip(Map<String, String> files, String name) throws Exception {
        Path path = temp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> item : files.entrySet()) {
                out.putNextEntry(new ZipEntry(item.getKey()));
                out.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return path;
    }
}
