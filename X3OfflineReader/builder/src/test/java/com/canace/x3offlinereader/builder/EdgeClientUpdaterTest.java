package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EdgeClientUpdaterTest {

    @TempDir
    Path directory;

    @Test
    void invalidPointersFallBackToBundle() throws Exception {
        assertEquals("bundled", EdgeClientUpdater.selection(directory));
        for (String pointer : new String[] {"{", "[]", "{\"version\":\"../outside\"}"}) {
            Files.writeString(directory.resolve("active.json"), pointer);
            assertEquals("bundled", EdgeClientUpdater.selection(directory));
        }
    }

    @Test
    void selectionIsSnapshotAndPreviousFilesAreRetained() throws Exception {
        Path client = directory.resolve("7.3.1/edge_tts");
        Files.createDirectories(client);
        Files.writeString(client.resolve("__init__.py"), "");
        Files.writeString(directory.resolve("active.json"), "{\"version\":\"7.3.1\"}");
        String pinned = EdgeClientUpdater.selection(directory);
        Files.writeString(directory.resolve("active.json"), "{\"version\":\"7.3.2\"}");
        assertEquals("7.3.1", pinned);
        assertEquals("bundled", EdgeClientUpdater.selection(directory));
    }

    @Test
    void unknownOperationIsRejectedBeforeStartingProcess() {
        assertThrows(IllegalArgumentException.class, () -> EdgeClientUpdater.run(directory, "bad"));
    }
}
