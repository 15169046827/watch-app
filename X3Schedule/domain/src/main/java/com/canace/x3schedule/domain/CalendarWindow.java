package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Read-only calendar projection. Browsing never materializes missing past records. */
public final class CalendarWindow {
    private CalendarWindow() {
    }

    public static List<Instance> read(List<Plan> plans, List<Instance> saved, List<Event> events,
            Settings settings, LocalDate first, LocalDate last, ZoneId zone, long now) {
        require(!last.isBefore(first) && ChronoUnit.DAYS.between(first, last) <= 42, "日历查询范围无效");
        List<Instance> fresh = new ArrayList<>();
        for (Plan plan : plans) {
            generate(plan, settings, first, last, zone, now, fresh);
        }
        Set<String> recorded = new HashSet<>();
        for (Event event : events) {
            recorded.add(event.instanceId);
        }
        List<Instance> result = new ArrayList<>();
        for (Instance item : Recurrence.reconcile(saved, fresh, recorded, now)) {
            if (intersects(item, first, last, zone)) {
                result.add(item);
            }
        }
        result.sort(Comparator.comparingLong((Instance item) -> item.start).thenComparing(item -> item.id));
        return result;
    }

    private static void generate(Plan plan, Settings settings, LocalDate first, LocalDate last,
            ZoneId zone, long now, List<Instance> fresh) {
        ZoneId planZone = ZoneId.of(plan.zone);
        LocalDate from = first.atStartOfDay(zone).withZoneSameInstant(planZone).toLocalDate()
                .minusDays(CalendarEditing.lookback(plan));
        LocalDate to = last.plusDays(1).atStartOfDay(zone).withZoneSameInstant(planZone).toLocalDate();
        LocalDate today = Instant.ofEpochMilli(now).atZone(planZone).toLocalDate();
        if (from.isBefore(today)) {
            from = today;
        }
        if (plan.kind == Kind.TODO) {
            fresh.addAll(Recurrence.generate(PlanEdits.withDefaults(plan, settings), today, today));
            return;
        }
        if (to.isBefore(from)) {
            return;
        }
        for (Instance item : Recurrence.generate(PlanEdits.withDefaults(plan, settings), from, to)) {
            if (item.start >= now || item.kind == Kind.TODO) {
                fresh.add(item);
            }
        }
    }

    public static boolean intersects(Instance item, LocalDate first, LocalDate last, ZoneId zone) {
        if (item.cancelled || !item.timed) {
            return false;
        }
        if (item.dateOnly) {
            LocalDate day = DayPrecision.day(item);
            return !day.plusDays(item.daySpan - 1L).isBefore(first) && !day.isAfter(last);
        }
        long start = first.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return item.start < end && Math.max(item.end, item.start + 1) > start;
    }

    public static List<Instance> onDay(List<Instance> items, LocalDate day, ZoneId zone) {
        List<Instance> result = new ArrayList<>();
        for (Instance item : items) {
            if (intersects(item, day, day, zone)) {
                result.add(item);
            }
        }
        return result;
    }

    public static final class Slot {
        public final Instance item;
        public final int start;
        public final int end;
        public int column;
        public int columns = 1;

        private Slot(Instance item, int start, int end) {
            this.item = item;
            this.start = start;
            this.end = end;
        }
    }

    /** Wall-clock positions with a minimum visual height. Labels retain exact times. */
    public static List<Slot> slots(List<Instance> items, LocalDate day, ZoneId zone) {
        List<Slot> slots = new ArrayList<>();
        long first = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long last = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (Instance item : onDay(items, day, zone)) {
            if (!item.dateOnly) {
                int start = item.start <= first ? 0 : minute(item.start, zone);
                int end = item.end >= last ? 1440 : minute(item.end, zone);
                slots.add(new Slot(item, start, Math.max(start + 45, end)));
            }
        }
        slots.sort(Comparator.comparingInt((Slot slot) -> slot.start).thenComparingInt(slot -> slot.end));
        assignColumns(slots);
        return slots;
    }

    private static int minute(long at, ZoneId zone) {
        java.time.LocalTime time = Instant.ofEpochMilli(at).atZone(zone).toLocalTime();
        return time.getHour() * 60 + time.getMinute();
    }

    private static void assignColumns(List<Slot> slots) {
        List<Slot> group = new ArrayList<>();
        PriorityQueue<Slot> active = new PriorityQueue<>(Comparator.comparingInt(slot -> slot.end));
        PriorityQueue<Integer> available = new PriorityQueue<>();
        int columns = 0;
        int groupEnd = -1;
        for (Slot slot : slots) {
            if (slot.start >= groupEnd) {
                finishGroup(group, columns);
                group.clear();
                active.clear();
                available.clear();
                columns = 0;
            }
            while (!active.isEmpty() && active.peek().end <= slot.start) {
                available.add(active.remove().column);
            }
            slot.column = available.isEmpty() ? columns++ : available.remove();
            active.add(slot);
            group.add(slot);
            groupEnd = group.size() == 1 ? slot.end : Math.max(groupEnd, slot.end);
        }
        finishGroup(group, columns);
    }

    private static void finishGroup(List<Slot> group, int columns) {
        for (Slot slot : group) {
            slot.columns = columns;
        }
    }
}
