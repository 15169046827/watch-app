package com.canace.x3offlinereader.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BuilderGuiTest {
    @Test void authorIsExactOrFallsBackWithoutUnknownPrefix(){
        assertEquals("未署名",AudiobookBuilder.effectiveAuthor(new BuilderOptions().author));
        assertEquals("张三",AudiobookBuilder.effectiveAuthor("  张三  "));
    }
    @Test void mergedSourceOutputUsesFirstSourceDirectoryAndChosenName(){
        Path input=Path.of("books", "first.txt");
        assertEquals(input.toAbsolutePath().getParent().resolve("小说 [张三].opbook"),BuilderGui.mergedSourceOutput(List.of(input),"小说","张三"));
    }
    @Test void separateSourceOutputUsesEachSourceNameAuthorAndDirectory(){
        Path input=Path.of("books", "volume-one.epub");
        assertEquals(input.toAbsolutePath().getParent().resolve("volume-one [未署名].opbook"),BuilderGui.sourceOutput(input,""));
    }
    @Test void suggestedNameSanitizesWindowsCharacters(){
        assertEquals("书_名 [作_者].opbook",BuilderGui.suggestedOutputName(List.of(),"书:名","作/者"));
    }
    @Test void etaUsesElapsedProgressAndReportsCompletion(){
        assertEquals("预计总用时：约 10:00　预计剩余：约 05:00",BuilderGui.etaText(300,600,false));
        assertEquals("实际用时：05:00　预计剩余：00:00",BuilderGui.etaText(300,300,true));
    }
    @Test void etaDoesNotGrowWhileProgressIsStalled(){
        BuilderGui.EtaTracker tracker=new BuilderGui.EtaTracker();tracker.seed(600);tracker.observe(50,300);long predicted=tracker.predicted();
        tracker.text(420,false);assertEquals(predicted,tracker.predicted());
    }
    @Test void textEstimateAccountsForProviderCacheAndLength(){
        long full=AudiobookBuilder.estimateSeconds("melo",7500,0,10,1.0);long cached=AudiobookBuilder.estimateSeconds("melo",0,1000,10,1.0);
        assertTrue(full>cached);assertTrue(full>900);
    }
    @Test void sherpaEstimateUsesMeasuredRealTimeThroughputRatherThanModelSpeedFactor(){
        assertTrue(AudiobookBuilder.estimateSeconds("sherpa-aishell3",7500,0,10,1.0)>2400);
        assertTrue(AudiobookBuilder.estimateSeconds("sherpa-kokoro",7500,0,10,1.0)>1800);
    }
    @Test void synthesisProgressStartsAtTenForWorkRemainingThisRun(){
        assertEquals(10,AudiobookBuilder.synthesisPercent(0,10_000));
        assertEquals(44,AudiobookBuilder.synthesisPercent(5_000,10_000));
        assertEquals(78,AudiobookBuilder.synthesisPercent(10_000,10_000));
    }
    @Test void pipelineProgressCombinesSpeechAndCompletedChapters(){
        assertEquals(10,AudiobookBuilder.pipelinePercent(0,100_000,0,100));
        assertEquals(78,AudiobookBuilder.pipelinePercent(100_000,100_000,0,100));
        assertEquals(84,AudiobookBuilder.pipelinePercent(100_000,100_000,50,100));
        assertEquals(90,AudiobookBuilder.pipelinePercent(100_000,100_000,100,100));
    }
}
