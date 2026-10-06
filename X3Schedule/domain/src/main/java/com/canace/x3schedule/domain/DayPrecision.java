package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar-day precision is independent from the time at which a reminder is delivered. */
public final class DayPrecision {
    private DayPrecision() {
    }

    public static void validate(Plan plan) {
        require(plan.daySpan >= 1 && plan.daySpan <= 366, "全天日期范围无效");
        require(plan.dateOnly || plan.daySpan == 1, "具体时间不使用全天范围");
        require(plan.kind != Kind.TODO || plan.daySpan == 1, "待办只使用截止日期");
        require(!plan.dateOnly || plan.kind != Kind.EXECUTION, "执行型需要具体开始时间");
        require(!plan.dateOnly || plan.kind != Kind.TODO || !plan.todoDue.isEmpty(), "按天截止需要日期");
        require(!plan.dateOnly || plan.kind != Kind.REMINDER || plan.durationMinutes == 0, "全天提醒不设置时长");
    }

    public static void validate(Instance item) {
        require(item.timed && item.kind != Kind.EXECUTION, "按天实例类型无效");
        require(item.daySpan >= 1 && item.daySpan <= 366, "全天日期范围无效");
        require(item.kind != Kind.TODO || item.daySpan == 1, "待办截止范围无效");
        ZoneId zone = ZoneId.of(item.zone);
        LocalDate day = day(item);
        long first = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long last = day.plusDays(item.daySpan).atStartOfDay(zone).toInstant().toEpochMilli();
        if (item.kind == Kind.TODO) {
            require(item.start == last && item.end == last, "按天待办截止边界无效");
        } else {
            require(item.start == first && item.end == last, "全天提醒范围无效");
        }
        require(item.start > 0, "按天日期无效");
    }

    public static LocalDate day(Instance item) {
        return item.kind == Kind.TODO ? Instant.ofEpochMilli(item.start).atZone(ZoneId.of(item.zone))
                .toLocalDate().minusDays(1) : Instant.ofEpochMilli(item.start)
                        .atZone(ZoneId.of(item.zone)).toLocalDate();
    }

    public static long reminderAt(Instance item, int minute) {
        require(minute >= 0 && minute < 1440, "默认提醒时间无效");
        return day(item).atStartOfDay().plusMinutes(minute).atZone(ZoneId.of(item.zone))
                .toInstant().toEpochMilli();
    }
}
