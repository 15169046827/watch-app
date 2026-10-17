package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import java.time.LocalDate;
import java.util.Collection;

/** Aggregates tasks equally; no daily-average averaging, invented zero or inferred action. */
public final class ProgressReport {
    private ProgressReport() {
    }

    public static class Summary {
        public int results;
        public int scoreSum;
        public int pending;
        public int perfect;
        public int basic;
        public int minimum;
        public int none;
        public int exempt;
        public int recoveryEnabled;
        public int recoveryActed;
        public int recoveryImproved;
        public int recoveryPerfect;
        public int manualActions;

        public Double score() {
            return results == 0 ? null : scoreSum * 1.0 / results;
        }
    }

    public static Summary range(Collection<Instance> items, Collection<Event> events,
            LocalDate from, LocalDate through, long now) {
        Summary report = new Summary();
        java.util.Map<String, java.util.List<Event>> indexed = Progress.index(events);
        for (Instance item : items) {
            if (item.progress == null || item.cancelled || item.start > now) {
                continue;
            }
            LocalDate day = LocalDate.parse(item.taskDay);
            if (!day.isBefore(from) && !day.isAfter(through)) {
                Progress.Summary state = Progress.summarize(item,
                        indexed.getOrDefault(item.id, java.util.Collections.emptyList()));
                if (state.grade != null || state.exempt() || item.end <= now) {
                    add(report, item, state, now);
                }
            }
        }
        return report;
    }

    private static void add(Summary report, Instance item, Progress.Summary state, long now) {
        report.recoveryEnabled += state.enabledCount;
        report.recoveryActed += state.scheduledActed ? 1 : 0;
        report.recoveryImproved += state.scheduledUpgrades > 0 ? 1 : 0;
        report.recoveryPerfect += state.scheduledGrade == Progress.Grade.PERFECT ? 1 : 0;
        report.manualActions += state.manualActions;
        if (state.exempt()) {
            report.exempt++;
        } else {
            if (state.grade != null) {
                count(report, state.grade);
            }
            if (awaitingFeedback(state, now)) {
                report.pending++;
            } else if (item.progress.scored) {
                report.results++;
                report.scoreSum += Progress.score(state.grade, state.deviations());
            }
        }
    }

    private static boolean awaitingFeedback(Progress.Summary state, long now) {
        return state.grade == null || state.recoveryConflict || state.enabled && !state.recoveryLeave
                && !state.scheduledActed && !state.recoveryMiss && now >= state.recoveryEnd;
    }

    private static void count(Summary report, Progress.Grade grade) {
        switch (grade) {
            case PERFECT:
                report.perfect++;
                break;
            case BASIC:
                report.basic++;
                break;
            case MINIMUM:
                report.minimum++;
                break;
            default:
                report.none++;
        }
    }

    public static int band(Double value) {
        if (value == null) {
            return -1;
        }
        if (value >= 80) {
            return 4;
        }
        if (value >= 60) {
            return 3;
        }
        if (value >= 30) {
            return 2;
        }
        return value >= 10 ? 1 : 0;
    }

    /** Floor avoids displaying 79.99 as 80 while retaining its lower colour band. */
    public static String display(Double value) {
        return value == null ? "— 暂无评分" : (int) Math.floor(value) + "分";
    }
}
