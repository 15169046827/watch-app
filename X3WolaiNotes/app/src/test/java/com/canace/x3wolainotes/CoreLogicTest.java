package com.canace.x3wolainotes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public final class CoreLogicTest {
    @Test
    public void validatesAndNormalizesPersonalTokens() {
        String valid = "  12345678901234567890  ";
        Assert.assertTrue(PersonalTokenPolicy.isValid(valid));
        Assert.assertEquals("12345678901234567890", PersonalTokenPolicy.normalize(valid));
        Assert.assertFalse(PersonalTokenPolicy.isValid(null));
        Assert.assertFalse(PersonalTokenPolicy.isValid("short"));
        Assert.assertFalse(PersonalTokenPolicy.isValid("12345678901234567890\nextra"));
        Assert.assertFalse(PersonalTokenPolicy.isValid(new String(new char[1025]).replace('\0', 'x')));
    }

    @Test
    public void validatesConfigurationAndResourceIds() {
        Assert.assertFalse(ConfigValidator.isConfigured(null));
        Assert.assertFalse(ConfigValidator.isConfigured("your_page_id"));
        Assert.assertTrue(ConfigValidator.isConfigured("page-id"));
        Assert.assertTrue(ApiInputPolicy.isValidResourceId("AbC_123-xyz"));
        Assert.assertFalse(ApiInputPolicy.isValidResourceId("../escape"));
        Assert.assertFalse(ApiInputPolicy.isValidResourceId("id?next=other"));
        Assert.assertFalse(ApiInputPolicy.isValidResourceId("id/children"));
        Assert.assertFalse(ApiInputPolicy.isValidResourceId(""));
    }

    @Test
    public void boundsRemoteText() {
        Assert.assertEquals("abc", ApiInputPolicy.boundedText(" abc ", 10));
        Assert.assertEquals("abc", ApiInputPolicy.boundedText("abcdef", 3));
        Assert.assertEquals("", ApiInputPolicy.boundedText(null, 3));
    }

    @Test
    public void normalizesNotesAndFilters() {
        Assert.assertEquals("hello world", NoteFormatter.normalize("  hello   world  "));
        Assert.assertEquals("☐ task", NoteFormatter.forWolai(NoteItem.KIND_TODO, "task"));
        Assert.assertTrue(ReaderFilter.matches("PROJECT done", "project", "Done"));
        Assert.assertFalse(ReaderFilter.matches("project missing", "project", "done"));
    }

    @Test
    public void reconcilesOnlyOneUncertainWrite() {
        long attemptedAt = 1_800_000_000_000L;
        NoteItem item = new NoteItem(
                "local",
                "same",
                NoteItem.KIND_NOTE,
                attemptedAt - 1_000L,
                NoteItem.STATE_UNCERTAIN,
                "",
                attemptedAt);
        List<SyncReconciler.Candidate> candidates = new ArrayList<>();
        candidates.add(new SyncReconciler.Candidate("remote", "same", attemptedAt + 500L));
        HashSet<String> consumed = new HashSet<>();

        Assert.assertEquals(
                "remote",
                SyncReconciler.findMatch(item, "same", candidates, consumed,
                        attemptedAt + 5_000L));
        consumed.add("remote");
        Assert.assertEquals(
                "",
                SyncReconciler.findMatch(item, "same", candidates, consumed,
                        attemptedAt + 5_000L));
        Assert.assertTrue(SyncReconciler.shouldWaitBeforeRetry(item,
                attemptedAt + 119_999L));
        Assert.assertFalse(SyncReconciler.shouldWaitBeforeRetry(item,
                attemptedAt + 120_000L));
    }
}
