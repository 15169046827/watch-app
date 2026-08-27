package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BuilderBridgeTest {
    @Test void completionReportsWholeTaskAndCurrentRunDurations(){
        var event=BuilderBridge.completionEvent(125,15,List.of("sample.opbook"));
        assertEquals("complete",event.path("type").asText());
        assertEquals(140,event.path("taskElapsedSeconds").asLong());
        assertEquals(15,event.path("runElapsedSeconds").asLong());
        assertEquals("sample.opbook",event.path("outputs").get(0).asText());
    }
}
