package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Recovery previews use a fingerprint so consent cannot apply to a changed timetable. */
public final class RecoveryPlanner {
    private RecoveryPlanner() {
    }

    public static class Impact {
        public Instance item;
        public double remaining;
        public boolean hard;
        public boolean mustYield;
        public long occupiedStart;
        public long occupiedEnd;
        public boolean structuralHard;
        public boolean recovery;

        public String label() {
            if (hard) {
                return item.title + " · 必须先调整日程";
            }
            return item.title + " · 最长连续剩余" + (int) Math.floor(remaining * 100) + "%"
                    + (mustYield ? " · 必须调整或确认本次让位" : remaining < 0.6 ? " · 建议调整或本次让位" : "");
        }
    }

    public static long[] standby(Instance item) {
        require(item.progress != null && item.progress.recoveryMinute >= 0, "尚未设置备用补救时段");
        LocalDate date = Instant.ofEpochMilli(item.start).atZone(ZoneId.of(item.zone)).toLocalDate()
                .plusDays(item.progress.recoveryDayOffset);
        long start = Progress.boundary(date, item.zone, item.progress.recoveryMinute);
        return new long[] { start, start + item.progress.recoveryDuration * 60000L };
    }

    public static long[] standby(Instance item, Progress.Summary state) {
        return state.standbyStart > 0 ? new long[] { state.standbyStart, state.standbyEnd } : standby(item);
    }

    public static List<Impact> preview(Instance owner, long start, long end, Collection<Instance> items,
            Collection<Event> events, long now) {
        return previewStates(owner, start, end, items, Progress.summaries(items, events), now);
    }

    static List<Impact> previewStates(Instance owner, long start, long end, Collection<Instance> items,
            Map<String, Progress.Summary> states, long now) {
        require(start >= owner.end && start > now && end > start && end <= owner.taskDayEnd,
                "补救须在原时段之后、未来且在同一任务日内");
        List<Impact> result = new ArrayList<>();
        for (Instance item : items) {
            if (item.id.equals(owner.id) || item.cancelled || !item.timed
                    || item.progress != null && item.progress.untimed) {
                continue;
            }
            Progress.Summary other = states.get(item.id);
            if (other != null && other.enabled && overlaps(start, end, other.recoveryStart, other.recoveryEnd)) {
                Impact conflict = new Impact();
                conflict.item = item;
                conflict.hard = true;
                conflict.structuralHard = true;
                conflict.recovery = true;
                conflict.occupiedStart = other.recoveryStart;
                conflict.occupiedEnd = other.recoveryEnd;
                result.add(conflict);
            } else if (overlaps(start, end, item.start, Math.max(item.start + 1, item.end))) {
                result.add(impact(item, start, end, now));
            }
        }
        result.sort(Comparator.comparing(i -> i.item.id));
        return result;
    }

    private static Impact impact(Instance item, long start, long end, long now) {
        Impact impact = new Impact();
        impact.item = item;
        long duration = item.end - item.start;
        impact.remaining = duration <= 0 ? 0 : Math.max(Math.max(0, start - item.start),
                Math.max(0, item.end - end)) * 1.0 / duration;
        impact.remaining = Math.min(1, impact.remaining);
        impact.occupiedStart = item.start;
        impact.occupiedEnd = item.end;
        impact.structuralHard = item.dateOnly || item.progress == null || !item.progress.allowYield;
        impact.hard = item.start <= now || impact.structuralHard;
        impact.mustYield = impact.remaining < 0.3;
        return impact;
    }

    public static boolean overlaps(long first, long last, long otherFirst, long otherLast) {
        return first < otherLast && otherFirst < last;
    }

    public static String fingerprint(Instance owner, long start, long end, List<Impact> impacts) {
        StringBuilder key = new StringBuilder(owner.id).append(':').append(start).append(':').append(end);
        for (Impact impact : impacts) {
            key.append('|').append(impact.item.id).append(':').append(impact.occupiedStart)
                    .append(':').append(impact.occupiedEnd).append(':').append(impact.recovery)
                    .append(':').append(impact.structuralHard).append(':').append(impact.remaining);
        }
        return Wire.digest(key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void requireConsent(Instance owner, Progress.Entry entry, List<Impact> impacts) {
        require(entry.conflictKey.equals(fingerprint(owner, entry.start, entry.end, impacts)),
                "日程已改变，请重新预览补救影响");
        for (Impact impact : impacts) {
            require(!impact.hard, "补救时段未能开启，存在日程冲突。请前往手机处理后重试。");
            require(!impact.mustYield || entry.yielded.contains(impact.item.id), "剩余不足30%，请调整或确认本次让位");
        }
        for (String id : entry.yielded) {
            require(impacts.stream().anyMatch(i -> !i.hard && i.item.id.equals(id)), "让位范围已改变");
        }
    }

    /** New plan edits cannot invalidate an already enabled recovery commitment. */
    public static void requireNoCommittedConflict(Collection<Instance> fresh, Collection<Instance> current,
            Collection<Event> events, long now) {
        Map<String, Progress.Summary> states = Progress.summaries(current, events);
        for (Instance owner : current) {
            if (owner.progress == null) {
                continue;
            }
            Progress.Summary state = states.get(owner.id);
            if (!state.enabled || state.recoveryEnd <= now) {
                continue;
            }
            for (Instance changed : fresh) {
                require(!conflictsWithCommitment(owner, state, changed, current),
                        "新安排与已启用补救冲突，请先取消或重排尚未开始的补救");
            }
        }
    }

    private static boolean conflictsWithCommitment(Instance owner, Progress.Summary state, Instance changed,
            Collection<Instance> current) {
        if (changed.id.equals(owner.id) || changed.cancelled || !changed.timed
                || changed.progress != null && changed.progress.untimed) {
            return false;
        }
        Instance prior = current.stream().filter(i -> i.id.equals(changed.id)).findFirst().orElse(null);
        if (prior != null && prior.start == changed.start && prior.end == changed.end
                && prior.cancelled == changed.cancelled) {
            return false;
        }
        return overlaps(changed.start, Math.max(changed.end, changed.start + 1),
                state.recoveryStart, state.recoveryEnd);
    }
}
