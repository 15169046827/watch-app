package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.ExceptionDay;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Segments share the original plan id and recurrence anchor. Existing execution snapshots stay frozen.
 */
public final class PlanEdits {

    private PlanEdits() {
    }

    public static Plan from(Plan original, Plan draft, LocalDate date, long now) {
        Kinds.requireChange(original, draft);
        require(original.kind != Models.Kind.TODO, "待办直接修改，不使用周期分段");
        require(original.id.equals(draft.id), "不能修改计划身份");
        require(!date.isBefore(Instant.ofEpochMilli(now).atZone(ZoneId.of(original.zone)).toLocalDate()), "修改仅影响未来");
        require(!date.isBefore(LocalDate.parse(original.anchor)), "生效日期早于计划起点");
        date = oneOffStart(original, draft, date, now);
        Plan result = Wire.copy(draft, Plan.class);
        result.previous = new ArrayList<>();
        List<Plan> earlier = new ArrayList<>(original.previous);
        Plan current = Wire.copy(original, Plan.class);
        current.previous.clear();
        earlier.add(current);
        for (Plan segment : earlier) {
            LocalDate start = LocalDate.parse(segment.effectiveFrom.isEmpty() ? segment.anchor : segment.effectiveFrom);
            if (!start.isBefore(date)) {
                continue;
            }
            Plan past = Wire.copy(segment, Plan.class);
            past.previous.clear();
            String last = date.minusDays(1).toString();
            if (past.until.isEmpty() || past.until.compareTo(last) > 0) {
                past.until = last;
            }
            if (!LocalDate.parse(past.until).isBefore(start)) {
                result.previous.add(past);
            }
        }
        result.effectiveFrom = date.toString();
        result.validate();
        return result;
    }

    private static LocalDate oneOffStart(Plan original, Plan draft, LocalDate date, long now) {
        LocalDate anchor = LocalDate.parse(draft.anchor);
        if (CalendarEditing.once(original) && original.previous.isEmpty()
                && date.equals(LocalDate.parse(original.anchor)) && anchor.isBefore(date)) {
            LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of(original.zone)).toLocalDate();
            require(!anchor.isBefore(today), "修改仅影响未来");
            return anchor;
        }
        return date;
    }

    public enum RosterSource { CONFIRMED, COMPANY, OFFICIAL }

    public static Plan at(Plan plan, LocalDate date) {
        return Wire.copy(segmentAt(plan, date), Plan.class);
    }

    /** The selected segment supplies the options; its split boundary is not a user-chosen series ending. */
    public static Plan followingDraft(Plan plan, LocalDate date) {
        Plan draft = at(plan, date);
        draft.until = plan.until;
        draft.count = plan.count;
        return draft;
    }

    public static Plan removeFrom(Plan original, LocalDate date, long now) {
        requireFuture(original, date, now);
        Plan result = Wire.copy(original, Plan.class);
        List<Plan> retained = new ArrayList<>();
        for (Plan segment : original.previous) {
            retainBefore(segment, date, retained);
        }
        Plan current = Wire.copy(original, Plan.class);
        current.previous.clear();
        retainBefore(current, date, retained);
        if (retained.isEmpty()) {
            result.deleted = true;
        } else {
            result = retained.remove(retained.size() - 1);
            result.previous = retained;
        }
        result.validate();
        return result;
    }

    private static void retainBefore(Plan segment, LocalDate date, List<Plan> retained) {
        LocalDate start = LocalDate.parse(segment.effectiveFrom.isEmpty() ? segment.anchor : segment.effectiveFrom);
        if (!start.isBefore(date)) {
            return;
        }
        Plan copy = Wire.copy(segment, Plan.class);
        copy.previous.clear();
        String end = date.minusDays(1).toString();
        if (copy.until.isEmpty() || copy.until.compareTo(end) > 0) {
            copy.until = end;
        }
        retained.add(copy);
    }

    /** A preview draft changes the segment that actually owns the selected date. */
    public static Plan exception(Plan original, LocalDate date, ExceptionDay exception, long now) {
        requireFuture(original, date, now);
        Plan result = Wire.copy(original, Plan.class);
        segmentAt(result, date).exceptions.put(date.toString(), Wire.copy(exception, ExceptionDay.class));
        result.validate();
        return result;
    }

    public static Plan roster(Plan original, LocalDate date, RosterSource source, boolean working, long now) {
        requireFuture(original, date, now);
        require(source != null, "排班来源无效");
        Plan result = Wire.copy(original, Plan.class);
        Plan segment = segmentAt(result, date);
        Map<String, Boolean> days;
        switch (source) {
            case COMPANY:
                days = segment.company;
                break;
            case OFFICIAL:
                days = segment.official;
                break;
            default:
                days = segment.confirmed;
                break;
        }
        days.put(date.toString(), working);
        result.validate();
        return result;
    }

    private static void requireFuture(Plan plan, LocalDate date, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of(plan.zone)).toLocalDate();
        require(!date.isBefore(today), "修改仅影响未来");
        require(!plan.deleted, "已删除计划不能修改");
        require(plan.kind != Models.Kind.TODO, "待办不使用单次排班");
    }

    static boolean covers(Plan segment, LocalDate date) {
        LocalDate start = LocalDate.parse(segment.anchor);
        if (!segment.effectiveFrom.isEmpty()) {
            LocalDate effective = LocalDate.parse(segment.effectiveFrom);
            if (effective.isAfter(start)) {
                start = effective;
            }
        }
        return !date.isBefore(start) && (segment.until.isEmpty() || !date.isAfter(LocalDate.parse(segment.until)));
    }

    private static Plan segmentAt(Plan plan, LocalDate date) {
        for (Plan earlier : plan.previous) {
            if (covers(earlier, date)) {
                return earlier;
            }
        }
        require(covers(plan, date), "日期不在计划有效范围内");
        return plan;
    }

    public static Plan withDefaults(Plan plan, Settings settings) {
        Plan copy = Wire.copy(plan, Plan.class);
        if (copy.inheritReminders) {
            copy.reminderMinutes = settings.reminderMinutes;
            copy.reminderTargets = settings.reminderTargets;
        }
        if (copy.dateOnly) {
            copy.reminderMinutes = 0;
        }
        for (int n = 0; n < copy.previous.size(); n++) {
            copy.previous.set(n, withDefaults(copy.previous.get(n), settings));
        }
        return copy;
    }
}
