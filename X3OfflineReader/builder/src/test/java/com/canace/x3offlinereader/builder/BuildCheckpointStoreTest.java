package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildCheckpointStoreTest {
    @TempDir Path temporary;

    @Test
    void roundTripsTaskOptionsAndRemovesCompletedCheckpoint() throws Exception {
        BuilderOptions options = new BuilderOptions();
        options.inputs.add(temporary.resolve("书.txt"));
        options.output = temporary.resolve("书 [作者].opbook");
        options.title = "书";
        options.author = "作者";
        options.tts = "melo";
        options.voice = "ZH";
        options.meloWorkers = 1;
        options.edgeWorkers = 10;
        options.sherpaWorkers = 3;
        options.windowsWorkers = 7;
        options.rate = 0.9;
        options.separate = true;
        BuildCheckpointStore store = new BuildCheckpointStore(temporary);
        BuildCheckpointStore.Checkpoint created = store.create(options);
        BuildCheckpointStore.Checkpoint paused = store.update(created, "paused", 37, "已保存并暂停", 125);
        BuildCheckpointStore.Checkpoint restored = store.latest().orElseThrow();
        assertEquals("paused", restored.state());
        assertEquals(37, restored.percent());
        assertEquals(125, restored.elapsedSeconds());
        assertEquals(options.inputs, restored.options().inputs);
        assertEquals(options.output, restored.options().output);
        assertEquals("作者", restored.options().author);
        assertEquals(1, restored.options().meloWorkers);
        assertEquals(10, restored.options().edgeWorkers);
        assertEquals(3, restored.options().sherpaWorkers);
        assertEquals(7, restored.options().windowsWorkers);
        assertEquals(0.9, restored.options().rate);
        assertTrue(restored.options().separate);
        store.complete(paused);
        assertFalse(store.latest().isPresent());
    }

    @Test
    void readsLegacyCheckpointWithoutElapsedTime() throws Exception {
        BuilderOptions options = new BuilderOptions();
        options.inputs.add(temporary.resolve("旧任务.txt"));
        options.output = temporary.resolve("旧任务.opbook");
        BuildCheckpointStore store = new BuildCheckpointStore(temporary);
        BuildCheckpointStore.Checkpoint created = store.create(options);
        Files.write(
                created.file(),
                Files.readAllLines(created.file()).stream()
                        .filter(
                                line ->
                                        !line.startsWith("elapsedSeconds=")
                                                && !line.startsWith("edgeWorkers=")
                                                && !line.startsWith("sherpaWorkers=")
                                                && !line.startsWith("windowsWorkers="))
                        .toList());
        assertEquals(0, store.latest().orElseThrow().elapsedSeconds());
        assertEquals(1, store.latest().orElseThrow().options().edgeWorkers);
        assertEquals(1, store.latest().orElseThrow().options().sherpaWorkers);
        assertEquals(1, store.latest().orElseThrow().options().windowsWorkers);
    }

    @Test
    void requestedStopThrowsDedicatedPauseSignal() {
        BuildControl control = new BuildControl();
        control.requestStop();
        assertThrows(BuildControl.PauseRequestedException.class, control::checkpoint);
    }
}
