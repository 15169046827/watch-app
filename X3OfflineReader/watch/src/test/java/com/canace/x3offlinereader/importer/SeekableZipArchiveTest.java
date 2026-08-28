package com.canace.x3offlinereader.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SeekableZipArchiveTest {
    @TempDir Path temporary;

    @Test void readsCentralDirectoryAndDeflatedEntriesWithoutSequentialScan() throws Exception {
        Path archive=temporary.resolve("sample.opbook");byte[] manifest="{\"format\":\"opbook\"}".getBytes(StandardCharsets.UTF_8),audio=new byte[]{0,0,0,12,'f','t','y','p',1,2,3,4};
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.setComment("central-directory-tail");write(zip,"manifest.json",manifest);write(zip,"audio/track.m4a",audio);}
        try(SeekableZipArchive zip=SeekableZipArchive.open(archive.toFile())){
            assertEquals(2,zip.entries().size());SeekableZipArchive.Entry entry=zip.getEntry("audio/track.m4a");assertNotNull(entry);assertEquals(audio.length,entry.size);
            try(InputStream input=zip.open(entry)){assertArrayEquals(audio,input.readAllBytes());}
            try(InputStream input=zip.open(zip.getEntry("manifest.json"))){assertArrayEquals(manifest,input.readAllBytes());}
        }
    }

    private static void write(ZipOutputStream zip,String name,byte[] value)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(value);zip.closeEntry();}
}
