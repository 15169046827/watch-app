package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuilderCacheManagerTest {
    @TempDir Path temporary;

    @Test void reportsAndBoundsSentenceCache()throws Exception{
        Path cache=temporary.resolve("builder/.work/cache");Files.createDirectories(cache);Files.write(cache.resolve("a.wav"),new byte[80]);Files.write(cache.resolve("b.wav"),new byte[80]);
        BuilderCacheManager manager=new BuilderCacheManager(temporary);assertEquals(2,manager.stats().sentenceFiles());BuilderCacheManager.PruneResult result=manager.pruneSentenceCache(80,Duration.ofDays(365));assertEquals(1,result.deletedFiles());assertTrue(manager.stats().sentenceBytes()<=80);
    }

    @Test void onlyDeletesOldBookWorkDirectories()throws Exception{
        Path work=temporary.resolve("builder/.work"),old=work.resolve("book-old"),cache=work.resolve("cache");Files.createDirectories(old);Files.createDirectories(cache);Files.write(old.resolve("track.wav"),new byte[32]);Files.write(cache.resolve("keep.wav"),new byte[32]);Files.setLastModifiedTime(old,java.nio.file.attribute.FileTime.fromMillis(0));
        BuilderCacheManager.PruneResult result=new BuilderCacheManager(temporary).clearStaleWork(Duration.ofDays(30));assertEquals(1,result.deletedFiles());assertFalse(Files.exists(old));assertTrue(Files.exists(cache.resolve("keep.wav")));
    }
}
