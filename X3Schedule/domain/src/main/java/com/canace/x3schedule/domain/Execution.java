package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Models.State;
import com.canace.x3schedule.domain.Models.Suggestion;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Execution {

    public static final Comparator<Event> ORDER = Comparator.comparing((Event e) -> e.correction)
            .thenComparingLong(e -> e.confirmedAt).thenComparingLong(e -> e.logical).thenComparing(e -> e.deviceId)
            .thenComparing(e -> e.id);

    private Execution() {
    }

    public static Event winner(Collection<Event> events, String instanceId) {
        return events.stream().filter(e -> instanceId.equals(e.instanceId)).max(ORDER).orElse(null);
    }

    /**
     * Index once when generating reports, instead of rescanning every event for each instance.
     */
    public static Map<String, Event> winners(Collection<Event> events) {
        Map<String, Event> result = new HashMap<>();
        for (Event event : events) {
            result.merge(event.instanceId, event, (a, b) -> ORDER.compare(a, b) >= 0 ? a : b);
        }
        return result;
    }

    /**
     * The earliest execution snapshot survives offline plan edits.
     */
    public static Instance frozen(Instance fallback, Collection<Event> events) {
        if (fallback.kind == Kind.TODO) {
            return Wire.copy(fallback, Instance.class);
        }
        Event first = events.stream().filter(e -> e.instanceId.equals(fallback.id) && e.snapshot != null
                && Progress.freezes(e))
                .min(Comparator.comparingLong((Event e) -> e.confirmedAt).thenComparing(e -> e.id)).orElse(null);
        return first == null ? fallback : Wire.copy(first.snapshot, Instance.class);
    }

    public static State state(Instance instance, Event event, long now) {
        if (instance.cancelled) {
            return State.CANCELLED;
        }
        if (instance.progress != null) {
            return progressState(instance, event, now);
        }
        if (instance.kind != Kind.EXECUTION) {
            return Kinds.state(instance, event, now);
        }
        if (event != null) {
            State explicit = explicitState(instance, event, now);
            if (explicit != null) {
                return explicit;
            }
        }
        if (now < instance.end) {
            return now < instance.start ? State.UPCOMING : State.RUNNING;
        }
        return now >= instance.end + 86400000L ? State.OVERDUE : State.PENDING;
    }

    private static State progressState(Instance instance, Event event, long now) {
        Collection<Event> events = event == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(event);
        return Progress.state(instance, Progress.summarize(instance, events), now);
    }

    private static State explicitState(Instance instance, Event event, long now) {
        switch (event.action) {
            case COMPLETE:
                if (event.actualAt == null) {
                    return State.COMPLETED_UNKNOWN;
                }
                return event.actualAt <= instance.end ? State.ON_TIME : State.LATE;
            case INCOMPLETE:
                return State.INCOMPLETE;
            case LEAVE:
                return State.LEAVE;
            case CONTINUE:
                return now < event.confirmedAt + event.continueMinutes * 60000L ? State.RUNNING : null;
            case START:
                return now < instance.end ? State.RUNNING : null;
            default:
                return null;
        }
    }

    public static Set<String> validLeaves(List<Instance> instances, Collection<Event> events, Map<String,
            Plan> plans, Settings settings) {
        List<Instance> sorted = new ArrayList<>(instances);
        sorted.sort(Comparator.comparingLong((Instance i) -> i.start).thenComparing(i -> i.id));
        Map<String, Event> latest = winners(events);
        Map<String, Integer> counts = new HashMap<>();
        Set<String> valid = new HashSet<>();
        for (Instance instance : sorted) {
            Event event = latest.get(instance.id);
            Plan plan = plans.get(instance.planId);
            if (instance.kind != Kind.EXECUTION || instance.cancelled || event == null
                    || event.action != Action.LEAVE || plan == null) {
                continue;
            }
            LocalDate date = LocalDate.parse(instance.date);
            String week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            String month = YearMonth.from(date).toString();
            boolean withinWeek = consumeQuota(counts, "w:" + week, plan.id, instance.category, plan.leaveWeek,
                    settings.categoryWeek, settings.leaveWeek);
            boolean withinMonth = consumeQuota(counts, "m:" + month, plan.id, instance.category, plan.leaveMonth,
                    settings.categoryMonth, settings.leaveMonth);
            if (withinWeek && withinMonth) {
                valid.add(instance.id);
            }
        }
        return valid;
    }

    private static boolean consumeQuota(Map<String, Integer> counts, String period, String planId, String category,
            int planLimit, Map<String, Integer> categoryLimits, int globalLimit) {
        String scope = "g";
        int limit = globalLimit;
        if (planLimit >= 0) {
            scope = "p:" + planId;
            limit = planLimit;
        } else if (categoryLimits.containsKey(category)) {
            scope = "c:" + category;
            limit = categoryLimits.get(category);
        }
        return counts.merge(period + ":" + scope, 1, Integer::sum) <= limit;
    }

    public static class Report {

        public int planned;

        public int completed;

        public int onTime;

        public int late;

        public int completedUnknown;

        public int running;

        public int leave;

        public int excessLeave;

        public int pending;

        public int overdue;

        public int incomplete;

        public long plannedMinutes;

        public long completedMinutes;

        public Double executionRate() {
            return ratio(completed, planned - leave);
        }

        public Double fulfillmentRate() {
            return ratio(completed, planned);
        }

        /**
         * On-time performance uses the same required-execution denominator.
         */
        public Double onTimeRate() {
            return ratio(onTime, planned - leave);
        }

        public Double plannedDurationRate() {
            return ratio(completedMinutes, plannedMinutes);
        }

        public int incompleteIncludingOverdue() {
            return incomplete + overdue;
        }

        private static Double ratio(long numerator, long denominator) {
            return denominator == 0 ? null : numerator * 1.0 / denominator;
        }

        void add(Instance instance, State state, boolean validLeave) {
            planned++;
            long minutes = (instance.end - instance.start) / 60000;
            plannedMinutes += minutes;
            switch (state) {
                case ON_TIME:
                    onTime++;
                    break;
                case LATE:
                    late++;
                    break;
                case COMPLETED_UNKNOWN:
                    completedUnknown++;
                    break;
                case LEAVE:
                    if (validLeave) {
                        leave++;
                    } else {
                        excessLeave++;
                    }
                    break;
                case PENDING:
                    pending++;
                    break;
                case OVERDUE:
                    overdue++;
                    break;
                case INCOMPLETE:
                    incomplete++;
                    break;
                case RUNNING:
                    running++;
                    break;
                default:
                    break;
            }
            completed = onTime + late + completedUnknown;
            if (state == State.ON_TIME || state == State.LATE || state == State.COMPLETED_UNKNOWN) {
                completedMinutes += minutes;
            }
        }
    }

    public static Report report(List<Instance> all, Collection<Event> events, Set<String> validLeaves, long from,
            long to, long now, String plan, String category) {
        Report report = new Report();
        Map<String, Event> latest = winners(events);
        for (Instance instance : all) {
            if (!inRange(instance, from, to, now) || (plan != null && !plan.equals(instance.planId))
                    || (category != null && !category.equals(instance.category))) {
                continue;
            }
            report.add(instance, state(instance, latest.get(instance.id), now), validLeaves.contains(instance.id));
        }
        return report;
    }

    static boolean inRange(Instance instance, long from, long to, long now) {
        return instance.progress == null && instance.kind == Kind.EXECUTION
                && instance.start >= from && instance.start < to
                && !instance.cancelled && instance.start <= now;
    }

    public static Suggestion suggest(String planId, int samples, int matching, String kind, String oldValue,
            String proposed, String futureFrom) {
        require(samples >= matching && matching >= 0, "样本无效");
        if (matching < 3) {
            return null;
        }
        Suggestion suggestion = new Suggestion();
        suggestion.planId = planId;
        suggestion.samples = samples;
        suggestion.matching = matching;
        suggestion.kind = kind;
        suggestion.formal = samples >= 6 && matching * 1.0 / samples >= 0.60;
        suggestion.oldValue = oldValue;
        suggestion.proposedValue = proposed;
        suggestion.futureFrom = futureFrom;
        suggestion.evidence = matching + " / " + samples;
        suggestion.accepted = false;
        return suggestion;
    }

    public static boolean quiet(int minute, int start, int end) {
        if (start == end) {
            return false;
        }
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }
}
