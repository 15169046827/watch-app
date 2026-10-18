package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Date/time editing rules shared by UI and regression tests. */
public final class CalendarEditing {
    public static final int MAX_MINUTES = 366 * 1440;

    private CalendarEditing() {
    }

    public static LocalDateTime start(Plan plan) {
        return LocalDate.parse(plan.anchor).atStartOfDay().plusMinutes(plan.startMinute);
    }

    public static LocalDateTime end(Plan plan) {
        if (plan.dateOnly) {
            return LocalDate.parse(plan.anchor).plusDays(plan.daySpan - 1L).atStartOfDay();
        }
        return start(plan).atZone(ZoneId.of(plan.zone)).plusMinutes(plan.durationMinutes).toLocalDateTime();
    }

    public static void times(Plan plan, LocalDateTime start, LocalDateTime end, boolean hasEnd) {
        require(start.getSecond() == 0 && start.getNano() == 0, "开始时间须精确到分钟");
        Plan draft = Wire.copy(plan, Plan.class);
        draft.anchor = start.toLocalDate().toString();
        draft.startMinute = start.getHour() * 60 + start.getMinute();
        if (plan.dateOnly) {
            long days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1;
            require(days > 0 && days <= 366, "结束日期须不早于开始日期，最长366天");
            draft.daySpan = (int) days;
            draft.durationMinutes = 0;
            draft.startMinute = 0;
        } else {
            require(!ZoneId.of(plan.zone).getRules().getValidOffsets(start).isEmpty(),
                    "开始时间因夏令时不存在，请重新选择");
            require(!hasEnd || !ZoneId.of(plan.zone).getRules().getValidOffsets(end).isEmpty(),
                    "结束时间因夏令时不存在，请重新选择");
            require(end.getSecond() == 0 && end.getNano() == 0, "结束时间须精确到分钟");
            long minutes = hasEnd ? Duration.between(start.atZone(ZoneId.of(plan.zone)),
                    end.atZone(ZoneId.of(plan.zone))).toMinutes() : 0;
            require(!hasEnd || minutes > 0, "结束时间必须晚于开始时间");
            require(minutes <= MAX_MINUTES, "单项日程最长366天");
            draft.durationMinutes = (int) minutes;
            draft.daySpan = 1;
        }
        plan.anchor = draft.anchor;
        plan.startMinute = draft.startMinute;
        plan.durationMinutes = draft.durationMinutes;
        plan.daySpan = draft.daySpan;
    }

    public static boolean once(Plan plan) {
        return plan.rule == Rule.DAYS && plan.interval == 1 && plan.count == 1;
    }

    public static String repeat(Plan plan) {
        if (once(plan)) {
            return "不重复";
        }
        switch (plan.rule) {
            case DAYS:
                return "每" + plan.interval + "天";
            case WEEKS:
                return "每" + plan.interval + "周 · " + weekdays(plan.weekdays);
            case MONTH_NTH:
                return "每" + plan.interval + "月 · 第" + plan.nth + "个周" + "一二三四五六日".charAt(plan.weekday - 1);
            case MONTH_DAY:
                return "每" + plan.interval + "月 · " + LocalDate.parse(plan.anchor).getDayOfMonth() + "日";
            case YEARLY:
                return "每" + plan.interval + "年 · " + LocalDate.parse(plan.anchor).getMonthValue() + "月"
                        + LocalDate.parse(plan.anchor).getDayOfMonth() + "日";
            case WORKDAYS:
                return "中国工作日（含排班覆盖）";
            case ALTERNATING:
                return "大小周 · 起始周" + (plan.anchorSingleRest ? "单休" : "双休");
            default:
                return "自定义轮班 · " + plan.rotation.length() + "天一轮";
        }
    }

    private static String weekdays(int mask) {
        StringBuilder text = new StringBuilder();
        for (int n = 0; n < 7; n++) {
            if ((mask & (1 << n)) != 0) {
                if (text.length() > 0) {
                    text.append("、");
                }
                text.append("周").append("一二三四五六日".charAt(n));
            }
        }
        return text.toString();
    }

    public static String until(Plan plan) {
        if (plan.count > 0) {
            return plan.count + "次";
        }
        return plan.until.isEmpty() ? "永不" : "至 " + plan.until;
    }

    public static int lookback(Plan plan) {
        int days = Math.max(plan.daySpan, (plan.durationMinutes + 1439) / 1440);
        for (Models.ExceptionDay exception : plan.exceptions.values()) {
            days = Math.max(days, (exception.duration + 1439) / 1440);
            if (exception.daySpan != null) {
                days = Math.max(days, exception.daySpan);
            }
        }
        for (Plan previous : plan.previous) {
            days = Math.max(days, lookback(previous));
        }
        return days + 1;
    }
}
