package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.ExceptionDay;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Recurrence {

    private Recurrence() {
    }

    private static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static boolean base(Plan p, LocalDate d) {
        LocalDate a = LocalDate.parse(p.anchor);
        long days = ChronoUnit.DAYS.between(a, d);
        if (days < 0) {
            return false;
        }
        int dow = d.getDayOfWeek().getValue();
        switch (p.rule) {
            case DAYS:
                return days % p.interval == 0;
            case WEEKS:
                return ChronoUnit.WEEKS.between(monday(a), monday(d)) % p.interval == 0 && (p
                        .weekdays & (1 << (dow - 1))) != 0;
            case MONTH_DAY:
            case YEARLY:
                return sameDate(p, a, d);
            case MONTH_NTH:
                return ChronoUnit.MONTHS.between(YearMonth.from(a), YearMonth.from(d)) % p.interval == 0 && dow == p
                        .weekday && (d.getDayOfMonth() - 1) / 7 + 1 == p.nth;
            case WORKDAYS:
                return dow <= 5;
            case ALTERNATING:
                boolean single = (ChronoUnit.WEEKS.between(monday(a), monday(d)) % 2 == 0) == p.anchorSingleRest;
                return dow <= (single ? 6 : 5);
            case ROTATION:
                return p.rotation.charAt((int) (days % p.rotation.length())) == '1';
            default:
                throw new IllegalArgumentException("不支持的规则");
        }
    }

    private static boolean sameDate(Plan plan, LocalDate anchor, LocalDate day) {
        if (plan.rule == Rule.MONTH_DAY) {
            return ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(day)) % plan.interval == 0
                    && day.getDayOfMonth() == anchor.getDayOfMonth();
        }
        return (day.getYear() - anchor.getYear()) % plan.interval == 0 && day.getMonth() == anchor.getMonth()
                && day.getDayOfMonth() == anchor.getDayOfMonth();
    }

    public static boolean works(Plan p, LocalDate d) {
        String k = d.toString();
        if (p.confirmed.containsKey(k)) {
            return p.confirmed.get(k);
        }
        if (p.company.containsKey(k)) {
            return p.company.get(k);
        }
        Boolean official = WorkCalendar.official(p, d);
        if (p.rule == Rule.WORKDAYS && official != null) {
            return official;
        }
        return base(p, d);
    }

    public static List<Instance> generate(Plan p, LocalDate from, LocalDate to) {
        p.validate();
        if (p.deleted) {
            return new ArrayList<>();
        }
        if (p.kind == Kind.TODO) {
            return java.util.Collections.singletonList(Kinds.todo(p));
        }
        Map<String, Instance> result = new TreeMap<>();
        for (Plan segment : p.previous) {
            for (Instance i : generateFlat(segment, from, to)) {
                i.planVersion = p.version;
                result.put(i.id, i);
            }
        }
        for (Instance i : generateFlat(p, from, to)) {
            result.put(i.id, i);
        }
        return new ArrayList<>(result.values());
    }

    private static List<Instance> generateFlat(Plan p, LocalDate from, LocalDate to) {
        p.validate();
        require(!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) <= 3660, "查询范围上限十年");
        List<Instance> out = new ArrayList<>();
        if (p.deleted) {
            return out;
        }
        LocalDate a = LocalDate.parse(p.anchor);
        LocalDate queryEnd = queryEnd(p, from, to);
        LocalDate last = p.until.isEmpty() ? queryEnd : LocalDate.parse(p.until);
        require(ChronoUnit.DAYS.between(a, queryEnd) <= 73050, "起点过早");
        int ordinal = 0;
        for (LocalDate d = a; !d.isAfter(queryEnd) && !d.isAfter(last); d = d.plusDays(1)) {
            ExceptionDay ex = p.exceptions.get(d.toString());
            if (!matches(p, d, ex)) {
                continue;
            }
            if (p.count > 0 && ++ordinal > p.count) {
                break;
            }
            if (!withinQuery(p, d, ex, from, to)) {
                continue;
            }
            Instance i = instance(p, d, ex);
            out.add(i);
        }
        return out;
    }

    private static boolean matches(Plan plan, LocalDate day, ExceptionDay exception) {
        return exception == null ? works(plan, day) : !exception.skip;
    }

    private static boolean withinQuery(Plan plan, LocalDate day, ExceptionDay exception, LocalDate from, LocalDate to) {
        LocalDate scheduled = exception == null || exception.scheduledDate == null
                ? day : LocalDate.parse(exception.scheduledDate);
        return !scheduled.isBefore(from) && !scheduled.isAfter(to)
                && (plan.effectiveFrom.isEmpty() || !day.isBefore(LocalDate.parse(plan.effectiveFrom)));
    }

    private static Instance instance(Plan p, LocalDate d, ExceptionDay ex) {
        Instance i = new Instance();
        i.kind = p.kind;
        i.dateOnly = p.dateOnly;
        i.daySpan = p.daySpan;
        i.id = p.id + ":" + d;
        i.planId = p.id;
        i.planVersion = p.version;
        i.date = d.toString();
        i.title = p.title;
        i.category = p.category;
        i.zone = p.zone;
        i.steps = new ArrayList<>(p.steps);
        int minute = ex == null ? p.startMinute : ex.minute;
        int duration = ex == null ? p.durationMinutes : ex.duration;
        if (ex != null) {
            i.daySpan = ex.daySpan == null ? i.daySpan : ex.daySpan;
            i.title = ex.title == null ? i.title : ex.title;
            i.category = ex.category == null ? i.category : ex.category;
            d = ex.scheduledDate == null ? d : LocalDate.parse(ex.scheduledDate);
        }
        // DST gaps shift forward; overlaps choose earlier offset. Duration is elapsed time.
        i.start = d.atStartOfDay().plusMinutes(minute).atZone(ZoneId.of(p.zone)).toInstant().toEpochMilli();
        i.end = i.start + duration * 60000L;
        if (p.dateOnly) {
            i.start = d.atStartOfDay(ZoneId.of(p.zone)).toInstant().toEpochMilli();
            i.end = d.plusDays(i.daySpan).atStartOfDay(ZoneId.of(p.zone)).toInstant().toEpochMilli();
        }
        i.reminderMinutes = p.reminderMinutes;
        i.reminderTargets = p.reminderTargets;
        i.disputed = WorkCalendar.disputed(p, d);
        return i;
    }

    private static LocalDate queryEnd(Plan plan, LocalDate from, LocalDate to) {
        LocalDate end = to;
        for (Map.Entry<String, ExceptionDay> entry : plan.exceptions.entrySet()) {
            ExceptionDay exception = entry.getValue();
            if (!exception.skip && exception.scheduledDate != null) {
                LocalDate scheduled = LocalDate.parse(exception.scheduledDate);
                LocalDate identity = LocalDate.parse(entry.getKey());
                if (!scheduled.isBefore(from) && !scheduled.isAfter(to) && identity.isAfter(end)) {
                    end = identity;
                }
            }
        }
        return end;
    }

    /**
     * Freeze started instances and anything with execution history; retain removed future instances as tombstones.
     */
    public static List<Instance> reconcile(List<Instance> old, List<Instance> fresh, Set<String> recorded, long now) {
        Map<String, Instance> result = new TreeMap<>();
        for (Instance i : fresh) {
            result.put(i.id, i);
        }
        for (Instance i : old) {
            if (i.kind != Kind.TODO && (i.start <= now || recorded.contains(i.id))) {
                result.put(i.id, i);
            } else if (!result.containsKey(i.id)) {
                Instance copy = Wire.copy(i, Instance.class);
                copy.cancelled = true;
                result.put(i.id, copy);
            }
        }
        return new ArrayList<>(result.values());
    }

    public static boolean overlaps(Instance a, Instance b) {
        if (a.cancelled || b.cancelled || !a.timed || !b.timed || a.id.equals(b.id)) {
            return false;
        }
        return a.start < Math.max(b.end, b.start + 1) && b.start < Math.max(a.end, a.start + 1);
    }
}
