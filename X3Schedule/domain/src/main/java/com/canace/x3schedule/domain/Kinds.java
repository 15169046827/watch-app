package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.State;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;

/** Purpose is independent of category. Old JSON defaults to execution. */
public final class Kinds {
    private Kinds() {
    }

    public static String label(Kind kind) {
        switch (kind) {
            case TODO:
                return "待办型";
            case REMINDER:
                return "仅提醒型";
            default:
                return "执行型";
        }
    }

    public static void validatePlan(Plan plan) {
        require(plan.kind != null && plan.todoDue != null, "日程类型无效");
        DayPrecision.validate(plan);
        if (plan.kind != Kind.TODO) {
            require(plan.todoDue.isEmpty(), "仅待办可使用截止时间");
            return;
        }
        require(plan.previous != null && plan.previous.isEmpty() && plan.effectiveFrom.isEmpty(),
                "待办不使用历史周期分段");
        require(plan.rule == Rule.DAYS && plan.count == 1 && plan.interval == 1, "待办为单项，不使用周期");
        require(plan.exceptions != null && plan.exceptions.isEmpty() && plan.until.isEmpty(), "待办不使用周期例外");
        if (!plan.todoDue.isEmpty()) {
            LocalDateTime due = plan.dateOnly ? LocalDate.parse(plan.todoDue).plusDays(1).atStartOfDay()
                    : LocalDateTime.parse(plan.todoDue);
            require(due.getSecond() == 0 && due.getNano() == 0, "截止时间精确到分钟");
            require(due.atZone(ZoneId.of(plan.zone)).toInstant().toEpochMilli() > 0, "截止时间无效");
        }
    }

    public static void validateInstance(Instance instance) {
        Progress.validateInstance(instance);
        require(instance.kind != null && instance.planVersion > 0, "实例类型或版本无效");
        if (instance.dateOnly) {
            DayPrecision.validate(instance);
            return;
        }
        require(instance.daySpan == 1, "具体时间实例不使用全天范围");
        if (!instance.timed) {
            require(instance.kind == Kind.TODO && instance.start == 0 && instance.end == 0, "无时间待办无效");
            return;
        }
        require(instance.start > 0 && instance.end >= instance.start
                && instance.end - instance.start <= CalendarEditing.MAX_MINUTES * 60000L, "实例时间无效");
        require(instance.kind != Kind.TODO || instance.start == instance.end, "待办仅有截止时间");
    }

    public static void validateAction(Instance instance, Action action) {
        require(instance.kind != Kind.REMINDER, "仅提醒日程不记录完成情况");
        require(instance.progress == null ? action != Action.PROGRESS : action == Action.PROGRESS,
                "请使用与任务版本一致的反馈方式");
        if (instance.kind == Kind.TODO) {
            require(action == Action.COMPLETE || action == Action.REOPEN, "待办只支持完成或重新打开");
        } else {
            require(action != Action.REOPEN, "重新打开仅用于待办");
        }
    }

    public static void validateEventPurpose(Instance current, Event event) {
        if (event.snapshot == null) {
            require(current.kind == Kind.EXECUTION, "此类型的操作需要快照");
            return;
        }
        require((current.kind == Kind.TODO) == (event.snapshot.kind == Kind.TODO),
                "执行记录不能改变待办身份");
    }

    public static void requireChange(Plan old, Plan draft) {
        require((old.kind == Kind.TODO) == (draft.kind == Kind.TODO), "待办与时间日程请分别新建，保留原有历史");
        if (old.kind == Kind.TODO) {
            require(old.anchor.equals(draft.anchor), "待办身份日期不能改变");
        }
    }

    public static Instance todo(Plan plan) {
        Instance instance = new Instance();
        instance.kind = Kind.TODO;
        instance.dateOnly = plan.dateOnly;
        instance.id = plan.id + ":" + plan.anchor;
        instance.planId = plan.id;
        instance.planVersion = plan.version;
        instance.date = plan.anchor;
        instance.title = plan.title;
        instance.category = plan.category;
        instance.zone = plan.zone;
        instance.steps = new ArrayList<>(plan.steps);
        instance.timed = !plan.todoDue.isEmpty();
        if (instance.timed) {
            LocalDateTime due = plan.dateOnly ? LocalDate.parse(plan.todoDue).plusDays(1).atStartOfDay()
                    : LocalDateTime.parse(plan.todoDue);
            instance.start = due.atZone(ZoneId.of(plan.zone)).toInstant().toEpochMilli();
            instance.end = instance.start;
        }
        instance.reminderMinutes = plan.reminderMinutes;
        instance.reminderTargets = plan.reminderTargets;
        return instance;
    }

    public static State state(Instance instance, Event event, long now) {
        if (instance.kind == Kind.REMINDER) {
            return now < (instance.dateOnly ? instance.end : instance.start)
                    ? State.REMINDER_UPCOMING : State.REMINDER_PAST;
        }
        if (event != null && event.action == Action.COMPLETE) {
            return State.TODO_DONE;
        }
        return instance.timed && instance.start <= now ? State.TODO_DUE : State.TODO_OPEN;
    }

    public static String stateLabel(State state) {
        switch (state) {
            case REMINDER_UPCOMING:
                return "等待提醒";
            case REMINDER_PAST:
                return "时间已到";
            case TODO_OPEN:
                return "待办";
            case TODO_DUE:
                return "已到截止时间";
            case TODO_DONE:
                return "已完成";
            default:
                return "日程调整已取消";
        }
    }
}
