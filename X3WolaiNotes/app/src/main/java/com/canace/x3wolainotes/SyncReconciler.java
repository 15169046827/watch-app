package com.canace.x3wolainotes;

import java.util.List;
import java.util.Set;

final class SyncReconciler {
    static final long MIN_RETRY_DELAY_MS = 120_000L;
    private static final long MATCH_LOOKBACK_MS = 300_000L;
    private static final long FUTURE_CLOCK_SKEW_MS = 60_000L;

    static final class Candidate {
        final String id;
        final String text;
        final long createdAt;

        Candidate(String id, String text, long createdAt) {
            this.id = id;
            this.text = text;
            this.createdAt = createdAt;
        }
    }

    private SyncReconciler() {
    }

    static String findMatch(NoteItem item, String expectedText, List<Candidate> candidates,
                            Set<String> consumedIds, long now) {
        if (item.state != NoteItem.STATE_UNCERTAIN || item.lastAttemptAt <= 0L) {
            return "";
        }
        long earliest = item.lastAttemptAt - MATCH_LOOKBACK_MS;
        long latest = now + FUTURE_CLOCK_SKEW_MS;
        Candidate best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (candidate.id == null || candidate.id.isEmpty() || consumedIds.contains(candidate.id)) {
                continue;
            }
            if (!expectedText.equals(candidate.text) || candidate.createdAt < earliest
                    || candidate.createdAt > latest) {
                continue;
            }
            long distance = Math.abs(candidate.createdAt - item.lastAttemptAt);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? "" : best.id;
    }

    static boolean shouldWaitBeforeRetry(NoteItem item, long now) {
        return item.state == NoteItem.STATE_UNCERTAIN && item.lastAttemptAt > 0L
                && now - item.lastAttemptAt < MIN_RETRY_DELAY_MS;
    }
}
