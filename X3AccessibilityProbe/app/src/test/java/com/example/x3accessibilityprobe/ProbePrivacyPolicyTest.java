package com.example.x3accessibilityprobe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProbePrivacyPolicyTest {

    @Test
    public void textSummaryDoesNotContainOriginalSensitiveText() {
        String summary = ProbePrivacyPolicy.summarizeText("支付宝付款成功 ¥123.45 张三");

        assertTrue(summary.contains("digitCount=5"));
        assertTrue(summary.contains("hasCurrency=true"));
        assertTrue(summary.contains("decimalAmountLike=true"));
        assertFalse(summary.contains("123.45"));
        assertFalse(summary.contains("张三"));
    }

    @Test
    public void snapshotPersistenceRequiresPaymentRoot() {
        assertTrue(ProbePrivacyPolicy.shouldPersistSnapshot("com.eg.android.AlipayGphone"));
        assertFalse(ProbePrivacyPolicy.shouldPersistSnapshot("com.example.unrelated"));
        assertFalse(ProbePrivacyPolicy.shouldPersistSnapshot(null));
    }

    @Test
    public void paymentTriggerReplacesPendingNonPaymentSnapshot() {
        assertEquals(
                ProbePrivacyPolicy.ScheduleAction.REPLACE_PENDING,
                ProbePrivacyPolicy.decideSnapshotSchedule(true, true, false, 100L, 750L));
        assertEquals(
                ProbePrivacyPolicy.ScheduleAction.KEEP_PENDING,
                ProbePrivacyPolicy.decideSnapshotSchedule(false, true, false, 100L, 750L));
        assertEquals(
                ProbePrivacyPolicy.ScheduleAction.KEEP_PENDING,
                ProbePrivacyPolicy.decideSnapshotSchedule(false, true, true, 100L, 750L));
    }

    @Test
    public void paymentSnapshotIsDeferredInsteadOfDroppedDuringThrottle() {
        assertEquals(
                ProbePrivacyPolicy.ScheduleAction.SCHEDULE,
                ProbePrivacyPolicy.decideSnapshotSchedule(true, false, false, 100L, 750L));
        assertEquals(650L, ProbePrivacyPolicy.snapshotDelay(true, 100L, 750L, 250L));
        assertEquals(
                ProbePrivacyPolicy.ScheduleAction.DROP,
                ProbePrivacyPolicy.decideSnapshotSchedule(false, false, false, 100L, 750L));
    }

    @Test
    public void metadataRemovesLineBreaksAndLimitsLength() {
        StringBuilder longValue = new StringBuilder("line1\n");
        for (int i = 0; i < 200; i++) {
            longValue.append('x');
        }
        String cleaned = ProbePrivacyPolicy.cleanMetadata(longValue);

        assertFalse(cleaned.contains("\n"));
        assertTrue(cleaned.endsWith("…"));
        assertEquals(181, cleaned.length());
    }
}
