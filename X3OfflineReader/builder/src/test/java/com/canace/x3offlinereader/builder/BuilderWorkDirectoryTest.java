package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuilderWorkDirectoryTest {
    @TempDir Path temporary;

    @Test void windowsPersistentRootLivesOutsideTheReplaceablePackage(){
        Path local=temporary.resolve("local-app-data");
        Path result=BuilderWorkDirectory.persistentRoot(Map.of("LOCALAPPDATA",local.toString()),temporary.toString(),"Windows 11");
        assertEquals(local.resolve("Canace/X3AudiobookBuilder/work").toAbsolutePath(),result);
        assertFalse(result.startsWith(temporary.resolve("project/build/windows-package")));
    }

    @Test void rejectsPersistentWorkInsideProjectOrAppImage(){
        Path project=temporary.resolve("project");
        assertThrows(IllegalArgumentException.class,()->BuilderWorkDirectory.validatePersistentRoot(project,project.resolve("builder/build/work")));
    }

    @Test void migratesCacheCheckpointAndTrackWorkThenRemovesLegacyCopy()throws Exception{
        Path legacy=temporary.resolve("package/builder/.work"),persistent=temporary.resolve("persistent/work");
        byte[] cache={1,2,3,4},checkpoint={5,6},track={7,8,9};
        Files.createDirectories(legacy.resolve("cache"));Files.write(legacy.resolve("cache/a.wav"),cache);
        Files.createDirectories(legacy.resolve("checkpoints"));Files.write(legacy.resolve("checkpoints/task.properties"),checkpoint);
        Files.createDirectories(legacy.resolve("book-public"));Files.write(legacy.resolve("book-public/track.m4a"),track);
        BuilderWorkDirectory.MigrationResult result=BuilderWorkDirectory.migrate(legacy,persistent);
        assertEquals(3,result.files());assertEquals(9,result.bytes());assertTrue(result.sourceRemoved());assertFalse(Files.exists(legacy));
        assertArrayEquals(cache,Files.readAllBytes(persistent.resolve("cache/a.wav")));
        assertArrayEquals(checkpoint,Files.readAllBytes(persistent.resolve("checkpoints/task.properties")));
        assertArrayEquals(track,Files.readAllBytes(persistent.resolve("book-public/track.m4a")));
    }

    @Test void reusesVerifiedExistingFileDuringInterruptedMigration()throws Exception{
        Path legacy=temporary.resolve("legacy"),persistent=temporary.resolve("persistent");byte[] bytes={1,3,5,7};
        Files.createDirectories(legacy.resolve("cache"));Files.write(legacy.resolve("cache/a.wav"),bytes);
        Files.createDirectories(persistent.resolve("cache"));Files.write(persistent.resolve("cache/a.wav"),bytes);
        BuilderWorkDirectory.MigrationResult result=BuilderWorkDirectory.migrate(legacy,persistent);
        assertEquals(1,result.files());assertTrue(result.sourceRemoved());assertFalse(Files.exists(legacy));
    }

    @Test void conflictingDestinationAbortsWithoutDeletingLegacyData()throws Exception{
        Path legacy=temporary.resolve("legacy"),persistent=temporary.resolve("persistent");
        Files.createDirectories(legacy.resolve("cache"));Files.write(legacy.resolve("cache/a.wav"),new byte[]{1});
        Files.createDirectories(persistent.resolve("cache"));Files.write(persistent.resolve("cache/a.wav"),new byte[]{2});
        assertThrows(IllegalStateException.class,()->BuilderWorkDirectory.migrate(legacy,persistent));
        assertTrue(Files.isRegularFile(legacy.resolve("cache/a.wav")));assertArrayEquals(new byte[]{2},Files.readAllBytes(persistent.resolve("cache/a.wav")));
    }
}
