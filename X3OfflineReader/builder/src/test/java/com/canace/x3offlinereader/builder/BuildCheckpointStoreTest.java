package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildCheckpointStoreTest {
    @TempDir Path temporary;

    @Test void roundTripsTaskOptionsAndRemovesCompletedCheckpoint()throws Exception{
        BuilderOptions options=new BuilderOptions();options.inputs.add(temporary.resolve("书.txt"));options.output=temporary.resolve("书 [作者].opbook");options.title="书";options.author="作者";options.tts="melo";options.voice="ZH";options.meloWorkers=1;options.rate=0.9;options.separate=true;
        BuildCheckpointStore store=new BuildCheckpointStore(temporary);BuildCheckpointStore.Checkpoint created=store.create(options);BuildCheckpointStore.Checkpoint paused=store.update(created,"paused",37,"已保存并暂停");
        BuildCheckpointStore.Checkpoint restored=store.latest().orElseThrow();assertEquals("paused",restored.state());assertEquals(37,restored.percent());assertEquals(options.inputs,restored.options().inputs);assertEquals(options.output,restored.options().output);assertEquals("作者",restored.options().author);assertEquals(1,restored.options().meloWorkers);assertEquals(0.9,restored.options().rate);assertTrue(restored.options().separate);
        store.complete(paused);assertFalse(store.latest().isPresent());
    }

    @Test void requestedStopThrowsDedicatedPauseSignal(){BuildControl control=new BuildControl();control.requestStop();assertThrows(BuildControl.PauseRequestedException.class,control::checkpoint);}
}
