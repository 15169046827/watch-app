package com.canace.x3offlinereader.builder.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
