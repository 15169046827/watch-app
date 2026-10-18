package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Event;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Corrections reference an immutable fact; projections change without rewriting the audit trail. */
public final class ProgressHistory {
    private ProgressHistory() {
    }

    private static final Comparator<Event> ORDER = Comparator.comparingLong((Event e) -> e.logical)
            .thenComparing(e -> e.deviceId).thenComparing(e -> e.id);

    public static void validate(Collection<Event> events) {
        project(events, true);
        validateRecoveryEvidence(events);
    }

    private static void validateRecoveryEvidence(Collection<Event> events) {
        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(ORDER);
        Map<String, Long> windows = new HashMap<>();
        for (Event event : ordered) {
            Progress.Entry entry = event.progress;
            if (entry == null) {
                continue;
            }
            if (entry.op == Progress.Op.RESCHEDULE) {
                require(windows.containsKey(event.instanceId), "缺少原补救承诺记录");
            }
            if (entry.op == Progress.Op.ENABLE || entry.op == Progress.Op.RESCHEDULE) {
                // Parallel offline branches may finish at different times. Keep evidence for both;
                // their scheduling conflict is reconciled explicitly on the phone, not by dropping facts.
                windows.merge(event.instanceId, entry.end, Math::min);
            } else if (entry.op == Progress.Op.RESULT && entry.session == Progress.Session.SCHEDULED) {
                Long end = windows.get(event.instanceId);
                require(end != null && event.confirmedAt >= end, "缺少补救承诺或补救尚未结束");
            }
        }
    }

    public static List<Event> effective(Collection<Event> events) {
        return project(events, false);
    }

    private static List<Event> project(Collection<Event> events, boolean strict) {
        Map<String, Event> facts = new HashMap<>();
        List<Event> corrections = new ArrayList<>();
        for (Event event : events) {
            if (event.progress != null && event.progress.op == Progress.Op.CORRECT) {
                corrections.add(event);
            } else {
                facts.put(event.id, event);
            }
        }
        corrections.sort(ORDER);
        for (Event correction : corrections) {
            Event target = facts.get(correction.progress.replacesId);
            if (!correctable(target, correction)) {
                require(!strict, "更正目标缺失、已经确认未进行，或引用不属于本次任务");
                continue;
            }
            Event projection = Wire.copy(target, Event.class);
            projection.progress.grade = correction.progress.grade;
            projection.progress.acted = correction.progress.acted;
            facts.put(target.id, projection);
        }
        return new ArrayList<>(facts.values());
    }

    private static boolean correctable(Event target, Event correction) {
        if (target == null || target.progress == null || target.progress.op != Progress.Op.RESULT) {
            return false;
        }
        return target.instanceId.equals(correction.instanceId)
                && target.progress.session == correction.progress.session
                && target.progress.acted
                && target.logical < correction.logical && target.confirmedAt <= correction.confirmedAt;
    }

    public static List<Event> correctableResults(String instanceId, Collection<Event> events) {
        List<Event> result = new ArrayList<>();
        for (Event event : effective(events)) {
            if (event.instanceId.equals(instanceId) && event.progress != null
                    && event.progress.op == Progress.Op.RESULT && event.progress.acted) {
                result.add(event);
            }
        }
        result.sort(Comparator.comparingLong((Event e) -> e.confirmedAt).reversed().thenComparing(e -> e.id));
        return result;
    }
}
