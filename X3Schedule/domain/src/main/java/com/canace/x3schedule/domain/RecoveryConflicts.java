package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Rolling seven-day conflicts remain visible even after notification acknowledgement. */
public final class RecoveryConflicts {
    private RecoveryConflicts() {
    }

    public static class Conflict {
        public Instance item;
        public long start;
        public long end;
        public String key;
        public List<RecoveryPlanner.Impact> impacts;
        public boolean acknowledged;
        public boolean blocked;
        public boolean committed;
    }

    public static List<Conflict> scan(Collection<Instance> items, Collection<Event> events, long now) {
        Map<String, Progress.Summary> states = Progress.summaries(items, events);
        List<Conflict> result = new ArrayList<>();
        for (Instance item : items) {
            Progress.Summary state = states.get(item.id);
            if (state == null || item.cancelled || item.progress.untimed || state.exempt()) {
                continue;
            }
            if (completedStandby(state)) {
                continue;
            }
            long[] window;
            if (state.enabled || state.recoveryConflict) {
                window = new long[] { state.recoveryStart, state.recoveryEnd };
            } else if (item.progress.recoveryMinute >= 0 || state.standbyStart > 0) {
                window = RecoveryPlanner.standby(item, state);
            } else {
                continue;
            }
            if (outsideWindow(state, window, now)) {
                continue;
            }
            add(result, item, state, window, items, states, now);
        }
        return result;
    }

    private static boolean completedStandby(Progress.Summary state) {
        return !state.enabled && !state.recoveryConflict && state.grade == Progress.Grade.PERFECT;
    }

    private static boolean outsideWindow(Progress.Summary state, long[] window, long now) {
        if (window[0] > now + 7 * 86400000L) {
            return true;
        }
        if (state.recoveryConflict) {
            return false;
        }
        return state.enabled ? window[1] <= now : window[0] <= now;
    }

    private static void add(List<Conflict> result, Instance item, Progress.Summary state, long[] window,
            Collection<Instance> items, Map<String, Progress.Summary> states, long now) {
        List<RecoveryPlanner.Impact> impacts;
        try {
            impacts = RecoveryPlanner.previewStates(item, window[0], window[1], items, states,
                    state.enabled || state.recoveryConflict ? Math.min(now, window[0] - 1) : now);
        } catch (IllegalArgumentException invalid) {
            // An invalid standby is shown as a hard conflict rather than quietly being enabled.
            RecoveryPlanner.Impact impact = new RecoveryPlanner.Impact();
            impact.item = item;
            impact.hard = true;
            impacts = java.util.Collections.singletonList(impact);
        }
        String key = RecoveryPlanner.fingerprint(item, window[0], window[1], impacts);
        if (!state.recoveryConflict && (impacts.isEmpty() || state.enabled && key.equals(state.consentKey))) {
            return;
        }
        Conflict conflict = new Conflict();
        conflict.item = item;
        conflict.start = window[0];
        conflict.end = window[1];
        conflict.impacts = impacts;
        conflict.key = key;
        conflict.acknowledged = state.acknowledged.contains(conflict.key);
        conflict.blocked = state.blocked.contains(conflict.key);
        conflict.committed = state.enabled || state.recoveryConflict;
        result.add(conflict);
    }
}
