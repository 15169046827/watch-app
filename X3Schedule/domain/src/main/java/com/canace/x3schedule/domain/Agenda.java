package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.State;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only home projection; the displayed day follows the viewing device's time zone. */
public final class Agenda {
    public final List<Instance> today = new ArrayList<>();
    public final Map<String, State> states = new HashMap<>();
    public final Map<String, Progress.Summary> progress = new HashMap<>();
    public Instance current;
    public Instance next;
    public int pending;
    public int todos;

    private Agenda() {
    }

    public static Agenda read(List<Instance> instances, List<Event> events, long now, ZoneId zone) {
        Agenda result = new Agenda();
        LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long first = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long last = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        Map<String, Event> winners = Execution.winners(events);
        result.progress.putAll(Progress.summaries(instances, events));
        List<Instance> sorted = new ArrayList<>(instances);
        sorted.sort(Comparator.comparingLong((Instance i) -> i.start).thenComparing(i -> i.id));
        for (Instance instance : sorted) {
            if (!instance.cancelled) {
                State state = Execution.state(instance, winners.get(instance.id), now);
                if (instance.progress != null) {
                    Progress.Summary summary = result.progress.get(instance.id);
                    state = Progress.state(instance, summary, now);
                }
                result.states.put(instance.id, state);
                result.include(instance, state, now, first, last);
            }
        }
        return result;
    }

    private void include(Instance item, State state, long now, long first, long last) {
        if (item.kind == Kind.TODO) {
            if (state != State.TODO_DONE) {
                todos++;
            }
            return;
        }
        if (state == State.PENDING || state == State.OVERDUE) {
            pending++;
        }
        if (item.start < last && (item.end > first || item.start >= first)) {
            today.add(item);
        }
        selectNext(item, state, now);
    }

    private void selectNext(Instance item, State state, long now) {
        if (item.dateOnly) {
            return;
        }
        boolean active = state == State.RUNNING
                || (item.kind == Kind.REMINDER && item.start <= now && item.end > now);
        if (current == null && active) {
            current = item;
        }
        if (next == null && (state == State.UPCOMING || state == State.REMINDER_UPCOMING)) {
            next = item;
        }
    }
}
