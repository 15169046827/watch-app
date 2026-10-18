package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Suggestion;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Conservative local observations from ended instances in the last ninety days. */
public final class Optimizer {
    private static final long MINUTE = 60000L;

    private Optimizer() {
    }

    public static class Advice {
        public Suggestion suggestion;
        public long version;
        public String title;
        public String reason;
        public boolean adjustable;
    }

    private static class Sample {
        Instance instance;
        Event end;
        Event start;
    }

    private static class Pattern {
        int samples;
        List<Integer> values = new ArrayList<>();

        void add(boolean match, int value) {
            samples++;
            if (match) {
                values.add(value);
            }
        }

        int median() {
            values.sort(Integer::compareTo);
            return values.get((values.size() - 1) / 2);
        }
    }

    public static List<Advice> analyze(List<Plan> plans, List<Instance> instances, List<Event> events, long now) {
        List<Sample> samples = samples(instances, events, now);
        List<Advice> result = new ArrayList<>();
        for (Plan plan : plans) {
            if (!plan.deleted && plan.kind == Kind.EXECUTION) {
                analyzePlan(plan, samples, now, result);
            }
        }
        return result;
    }

    private static List<Sample> samples(List<Instance> instances, List<Event> events, long now) {
        Map<String, List<Event>> grouped = new HashMap<>();
        for (Event event : events) {
            if (event.confirmedAt <= now) {
                grouped.computeIfAbsent(event.instanceId, key -> new ArrayList<>()).add(event);
            }
        }
        List<Sample> result = new ArrayList<>();
        for (Instance saved : instances) {
            List<Event> history = grouped.getOrDefault(saved.id, new ArrayList<>());
            Instance instance = Execution.frozen(saved, history);
            Event end = Execution.winner(history, instance.id);
            if (eligible(instance, end, now)) {
                Sample sample = new Sample();
                sample.instance = instance;
                sample.end = end;
                sample.start = history.stream().filter(e -> e.action == Action.START && e.actualAt != null)
                        .max(Execution.ORDER).orElse(null);
                result.add(sample);
            }
        }
        result.sort(Comparator.comparingLong((Sample s) -> s.instance.start).thenComparing(s -> s.instance.id));
        return result;
    }

    private static boolean eligible(Instance instance, Event end, long now) {
        return instance.kind == Kind.EXECUTION && !instance.cancelled && !instance.disputed && instance.end <= now
                && instance.start >= now - 90 * 86400000L && end != null && end.action == Action.COMPLETE;
    }

    private static boolean sameDefinition(Plan plan, Instance instance) {
        int minute = Instant.ofEpochMilli(instance.start).atZone(ZoneId.of(instance.zone)).getHour() * 60
                + Instant.ofEpochMilli(instance.start).atZone(ZoneId.of(instance.zone)).getMinute();
        return plan.id.equals(instance.planId) && plan.version == instance.planVersion
                && plan.zone.equals(instance.zone) && minute == plan.startMinute
                && instance.end - instance.start == plan.durationMinutes * MINUTE;
    }

    private static void analyzePlan(Plan plan, List<Sample> all, long now, List<Advice> out) {
        Pattern start = new Pattern();
        Pattern duration = new Pattern();
        Pattern confirmation = new Pattern();
        Pattern overlap = new Pattern();
        Map<Integer, Pattern> weekdays = new java.util.TreeMap<>();
        for (Sample sample : all) {
            if (sameDefinition(plan, sample.instance)) {
                collect(sample, all, start, duration, confirmation, overlap, weekdays);
            }
        }
        add(out, plan, start, "START", "开始偏晚", "记录的实际开始比计划晚至少 5 分钟。",
                plan.startMinute, now);
        add(out, plan, duration, "DURATION", "计划时长可能不足", "实际开始至完成的间隔超出计划至少 5 分钟。",
                plan.durationMinutes, now);
        add(out, plan, confirmation, "CONFIRM", "完成后确认偏晚", "已完成但时间未知，或确认比实际完成晚至少 15 分钟。",
                0, now);
        add(out, plan, overlap, "OVERLAP", "与前序日程重叠", "开始偏晚时，前序日程仍未完成；相关性不能证明原因。",
                0, now);
        for (Map.Entry<Integer, Pattern> entry : weekdays.entrySet()) {
            add(out, plan, entry.getValue(), "WEEKDAY", "星期 " + entry.getKey() + " 开始偏晚",
                    "仅比较该星期有实际开始时间的记录，不自动改变周期。", 0, now);
        }
    }

    private static void collect(Sample sample, List<Sample> all, Pattern start, Pattern duration,
            Pattern confirmation, Pattern overlap, Map<Integer, Pattern> weekdays) {
        Event end = sample.end;
        confirmation.add(end.completedEarlier || (end.actualAt != null
                && end.confirmedAt - end.actualAt >= 15 * MINUTE), 0);
        if (sample.start == null) {
            return;
        }
        long actual = sample.start.actualAt;
        // A start after completion is inconsistent evidence, not a duration sample.
        if (end.actualAt != null && actual > end.actualAt) {
            return;
        }
        int delay = (int) ((actual - sample.instance.start) / MINUTE);
        boolean late = delay >= 5;
        start.add(late, delay);
        int weekday = LocalDate.parse(sample.instance.date).getDayOfWeek().getValue();
        weekdays.computeIfAbsent(weekday, key -> new Pattern()).add(late, delay);
        overlap.add(late && precedingOverlap(sample, all), 0);
        if (end.actualAt != null) {
            int minutes = (int) ((end.actualAt - actual) / MINUTE);
            int planned = (int) ((sample.instance.end - sample.instance.start) / MINUTE);
            duration.add(minutes >= planned + 5, minutes);
        }
    }

    private static boolean precedingOverlap(Sample current, List<Sample> all) {
        for (Sample prior : all) {
            long planned = current.instance.start;
            if (!prior.instance.planId.equals(current.instance.planId) && prior.start != null
                    && prior.end.actualAt != null && prior.instance.start < planned
                    && prior.instance.start >= planned - 86400000L && prior.start.actualAt <= planned
                    && prior.end.actualAt > planned && prior.end.actualAt <= current.start.actualAt) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Advice> out, Plan plan, Pattern pattern, String kind, String title,
            String reason, int oldValue, long now) {
        if (pattern.values.size() < 3) {
            return;
        }
        int proposed = proposed(kind, oldValue, pattern.median());
        LocalDate from = Instant.ofEpochMilli(now).atZone(ZoneId.of(plan.zone)).toLocalDate().plusDays(1);
        from = later(from, LocalDate.parse(plan.anchor));
        if (!plan.effectiveFrom.isEmpty()) {
            from = later(from, LocalDate.parse(plan.effectiveFrom));
        }
        Advice advice = new Advice();
        advice.suggestion = Execution.suggest(plan.id, pattern.samples, pattern.values.size(), kind,
                Integer.toString(oldValue), Integer.toString(proposed), from.toString());
        advice.version = plan.version;
        advice.title = plan.title + " · " + title;
        advice.reason = reason;
        advice.adjustable = (kind.equals("START") || kind.equals("DURATION")) && proposed != oldValue
                && proposed >= 0 && proposed <= (kind.equals("START") ? 1439 : 1440)
                && (plan.until.isEmpty() || from.compareTo(LocalDate.parse(plan.until)) <= 0);
        out.add(advice);
    }

    private static int proposed(String kind, int oldValue, int median) {
        int rounded = ((median + 4) / 5) * 5;
        if (kind.equals("START")) {
            return oldValue + rounded;
        }
        return kind.equals("DURATION") ? rounded : oldValue;
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    public static Plan preview(Plan current, Advice advice, long now) {
        Suggestion suggestion = advice.suggestion;
        require(advice.adjustable && suggestion.formal && !suggestion.accepted, "该观察尚不能应用");
        require(current.kind == Kind.EXECUTION && !current.deleted && current.id.equals(suggestion.planId)
                && current.version == advice.version,
                "计划已改变，请重新分析");
        LocalDate from = LocalDate.parse(suggestion.futureFrom);
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of(current.zone)).toLocalDate();
        require(from.isAfter(today), "生效日期已过，请重新分析");
        require(current.until.isEmpty() || from.compareTo(LocalDate.parse(current.until)) <= 0, "计划已结束");
        Plan draft = Wire.copy(current, Plan.class);
        int proposed = Integer.parseInt(suggestion.proposedValue);
        if ("START".equals(suggestion.kind)) {
            draft.startMinute = proposed;
        } else {
            require("DURATION".equals(suggestion.kind), "此类观察不能直接应用");
            draft.durationMinutes = proposed;
        }
        return PlanEdits.from(current, draft, from, now);
    }
}
