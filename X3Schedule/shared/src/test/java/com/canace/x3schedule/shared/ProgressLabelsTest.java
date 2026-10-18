package com.canace.x3schedule.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Progress;
import org.junit.Test;

public class ProgressLabelsTest {
    @Test
    public void perfectActualResultAfterMissIsNeverLabelledOnTime() {
        Instance item = new Instance();
        item.start = 1;
        Progress.Summary progress = new Progress.Summary();
        progress.grade = Progress.Grade.PERFECT;
        progress.originalMiss = true;
        String label = AgendaCards.progressLabel(item, progress);
        assertTrue(label.contains("完整完成"));
        assertTrue(label.contains("偏离1次"));
        assertFalse(label.contains("按时"));
    }
}
