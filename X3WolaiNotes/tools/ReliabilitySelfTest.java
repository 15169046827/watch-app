package com.canace.x3wolainotes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReliabilitySelfTest {
    private ReliabilitySelfTest() {
    }

    public static void main(String[] args) {
        verifyConfigValidation();
        verifyRemoteReconciliation();
        verifyRetryDelay();
        verifyErrorMessages();
        verifyReaderFilter();
        System.out.println("RELIABILITY_SELF_TEST_OK");
    }

    private static void verifyConfigValidation() {
        require(!ConfigValidator.isConfigured(null), "null config accepted");
        require(!ConfigValidator.isConfigured("  "), "blank config accepted");
        require(!ConfigValidator.isConfigured("your_page_id"), "example config accepted");
        require(!ConfigValidator.isConfigured("replace_me"), "replacement config accepted");
        require(ConfigValidator.isConfigured("page-id"), "configured value rejected");
    }

    private static void verifyRemoteReconciliation() {
        long attemptAt = 1_800_000_000_000L;
        NoteItem first = uncertain("one", attemptAt);
        NoteItem second = uncertain("two", attemptAt + 1000L);
        List<SyncReconciler.Candidate> candidates = new ArrayList<>();
        candidates.add(new SyncReconciler.Candidate("old", "same", attemptAt - 301_000L));
        candidates.add(new SyncReconciler.Candidate("wrong", "different", attemptAt));
        candidates.add(new SyncReconciler.Candidate("match", "same", attemptAt + 500L));
        Set<String> consumed = new HashSet<>();

        String firstMatch = SyncReconciler.findMatch(first, "same", candidates, consumed,
                attemptAt + 5_000L);
        require("match".equals(firstMatch), "matching remote block not found");
        consumed.add(firstMatch);
        require(SyncReconciler.findMatch(second, "same", candidates, consumed,
                attemptAt + 5_000L).isEmpty(), "remote block matched twice");
    }

    private static void verifyRetryDelay() {
        long attemptAt = 1_800_000_000_000L;
        NoteItem item = uncertain("wait", attemptAt);
        require(SyncReconciler.shouldWaitBeforeRetry(item, attemptAt + 119_999L),
                "uncertain write retried too early");
        require(!SyncReconciler.shouldWaitBeforeRetry(item, attemptAt + 120_000L),
                "uncertain write never became retryable");
    }

    private static void verifyErrorMessages() {
        require(UserErrorMessages.syncStatus(401).startsWith("授权已失效"),
                "sync authorization message missing");
        require(UserErrorMessages.syncStatus(403).contains("权限"),
                "sync permission message missing");
        require(UserErrorMessages.syncStatus(404).contains("不可用"),
                "sync missing-page message missing");
        require(UserErrorMessages.syncStatus(429).contains("请求过快"),
                "sync rate-limit message missing");
        require(UserErrorMessages.readerStatus(503).contains("服务暂时不可用"),
                "reader service message missing");
        require(UserErrorMessages.readerStatus(0).startsWith("网络不可用"),
                "reader network message missing");
    }

    private static void verifyReaderFilter() {
        require(ReaderFilter.matches("", "anything"), "blank filter rejected content");
        require(ReaderFilter.matches("WOLAI", "wolai notes"), "case-insensitive match failed");
        require(ReaderFilter.matches("项目 进行", "项目名称", "正在进行"),
                "multi-term row match failed");
        require(!ReaderFilter.matches("项目 完成", "项目名称", "正在进行"),
                "missing term was accepted");
        require("a b".equals(ReaderFilter.normalize("  a   b  ")),
                "filter whitespace normalization failed");
        require(ReaderFilter.normalize("12345678901234567890123456789012345678901")
                        .length() == ReaderFilter.MAX_QUERY_LENGTH,
                "filter length limit failed");
    }

    private static NoteItem uncertain(String id, long attemptAt) {
        return new NoteItem(id, "same", NoteItem.KIND_NOTE, attemptAt - 1000L,
                NoteItem.STATE_UNCERTAIN, "", attemptAt);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
