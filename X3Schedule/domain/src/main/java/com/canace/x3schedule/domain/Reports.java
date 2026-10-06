package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.State;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calendar ranges and one-pass aggregation of saved instance history.
 */
public final class Reports {

    public enum Period {

        DAY, WEEK, MONTH, YEAR
    }

    public static final class Range {

        public final LocalDate first;

        public final LocalDate last;

        public final long from;

        public final long until;

        public Range(LocalDate first, LocalDate last, ZoneId zone) {
            require(first.getYear() >= 1 && last.getYear() <= 9999, "报告年份须为 1–9999");
            require(!last.isBefore(first), "结束日期不能早于开始日期");
            this.first = first;
            this.last = last;
            from = first.atStartOfDay(zone).toInstant().toEpochMilli();
            until = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
    }

    public static final class Group {

        public final String category;

        public String title;

        public final Execution.Report report = new Execution.Report();

        private Group(Instance instance) {
            category = instance.category;
            title = instance.title;
        }
    }

    public static final class Summary {

        public final Execution.Report total = new Execution.Report();

        public final Map<String, Execution.Report> categories = new TreeMap<>();

        public final Map<String, Group> plans = new TreeMap<>();
    }

    private Reports() {
    }

    public static Range range(Period period, LocalDate anchor, ZoneId zone) {
        LocalDate first;
        LocalDate last;
        switch (period) {
            case WEEK:
                first = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                last = first.plusDays(6);
                break;
            case MONTH:
                first = anchor.withDayOfMonth(1);
                last = anchor.with(TemporalAdjusters.lastDayOfMonth());
                break;
            case YEAR:
                first = anchor.withDayOfYear(1);
                last = anchor.with(TemporalAdjusters.lastDayOfYear());
                break;
            default:
                first = anchor;
                last = anchor;
                break;
        }
        return new Range(first, last, zone);
    }

    /**
     * Compute valid leaves over all saved history before applying any report filter.
     */
    public static Summary summarize(List<Instance> instances, Collection<Event> events, Set<String> validLeaves,
            Range range, long now, String category) {
        Summary result = new Summary();
        Map<String, Event> latest = Execution.winners(events);
        List<Instance> sorted = new ArrayList<>(instances);
        sorted.sort(Comparator.comparingLong((Instance i) -> i.start).thenComparing(i -> i.id));
        for (Instance instance : sorted) {
            if (!Execution.inRange(instance, range.from, range.until, now) || (category != null && !category
                    .equals(instance.category))) {
                continue;
            }
            State state = Execution.state(instance, latest.get(instance.id), now);
            boolean validLeave = validLeaves.contains(instance.id);
            result.total.add(instance, state, validLeave);
            result.categories.computeIfAbsent(instance.category, ignored -> new Execution.Report()).add(instance,
                    state, validLeave);
            // JSON encoding avoids delimiter collisions in user-provided category names.
            String key = Wire.JSON.toJson(new String[] { instance.category, instance.planId });
            Group group = result.plans.computeIfAbsent(key, ignored -> new Group(instance));
            group.title = instance.title;
            group.report.add(instance, state, validLeave);
        }
        return result;
    }
}
