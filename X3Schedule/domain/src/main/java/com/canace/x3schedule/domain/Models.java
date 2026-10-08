package com.canace.x3schedule.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Wire models contain primitives only. Dates are ISO local dates; timestamps are epoch milliseconds.
 */
public final class Models {

    private Models() {
    }

    public enum Rule {

        DAYS,
        WEEKS,
        MONTH_NTH,
        WORKDAYS,
        ALTERNATING,
        ROTATION,
        MONTH_DAY,
        YEARLY
    }

    public enum Kind { EXECUTION, REMINDER, TODO }

    public enum Action {

        START, COMPLETE, CONTINUE, INCOMPLETE, LEAVE, REOPEN
    }

    public enum State {

        UPCOMING,
        RUNNING,
        PENDING,
        OVERDUE,
        ON_TIME,
        LATE,
        COMPLETED_UNKNOWN,
        INCOMPLETE,
        LEAVE,
        CANCELLED,
        REMINDER_UPCOMING, REMINDER_PAST, TODO_OPEN, TODO_DUE, TODO_DONE
    }

    public static class Plan {

        public Kind kind = Kind.EXECUTION;

        public boolean dateOnly;

        public int daySpan = 1;

        public String todoDue = "";

        public String id = UUID.randomUUID().toString();

        public String category = "日常";

        public String title = "";

        public String zone = "Asia/Shanghai";

        public long version = 1;

        public boolean deleted;

        public boolean inheritReminders;

        public String effectiveFrom = "";

        public List<Plan> previous = new ArrayList<>();

        public String anchor = LocalDate.now().toString();

        public String until = "";

        public Rule rule = Rule.WEEKS;

        public int interval = 1;

        public int weekdays = 31;

        public int nth = 1;

        public int weekday = 1;

        public int count;

        public int startMinute = 390;

        public int durationMinutes = 60;

        public boolean anchorSingleRest = true;

        public String rotation = "1111100";

        public List<String> steps = new ArrayList<>();

        // bit 1 phone, bit 2 watch
        public int reminderMinutes = 0;

        // bit 1 phone, bit 2 watch
        public int reminderTargets = 1;

        public int leaveWeek = -1;

        public int leaveMonth = -1;

        // ISO date -> WORK/REST. User confirmation > company > official > rule.
        public Map<String, Boolean> company = new TreeMap<>();

        // ISO date -> WORK/REST. User confirmation > company > official > rule.
        public Map<String, Boolean> confirmed = new TreeMap<>();

        // ISO date -> WORK/REST. User confirmation > company > official > rule.
        public Map<String, Boolean> official = new TreeMap<>();

        public Map<String, ExceptionDay> exceptions = new TreeMap<>();

        public void validate() {
            Kinds.validatePlan(this);
            validateIdentity();
            validateDates();
            validateSegments();
            validateFrequency();
            validateOptions();
            validateOverrides();
        }

        private void validateIdentity() {
            require(id != null && id.matches("[A-Za-z0-9_-]{1,80}"), "计划 ID 无效");
            require(title != null && !title.trim().isEmpty() && title.length() <= 160, "名称须为 1–160 字符");
            require(category != null && !category.trim().isEmpty() && category.length() <= 80, "大类无效");
        }

        private void validateDates() {
            ZoneId.of(zone);
            LocalDate a = LocalDate.parse(anchor);
            require(until != null && (until.isEmpty() || !LocalDate.parse(until).isBefore(a)), "结束日期早于起点");
        }

        private void validateSegments() {
            require(effectiveFrom != null && previous != null && previous.size() <= 32, "修改分段数量无效");
            if (!effectiveFrom.isEmpty()) {
                LocalDate.parse(effectiveFrom);
            }
            String lastEnd = "";
            for (Plan segment : previous) {
                require(segment != null && segment.previous != null && segment.previous.isEmpty() && id
                        .equals(segment.id) && !segment.until.isEmpty(), "历史规则分段无效");
                segment.validate();
                String start = segment.effectiveFrom.isEmpty() ? segment.anchor : segment.effectiveFrom;
                require(start.compareTo(lastEnd) > 0 && (effectiveFrom.isEmpty() || segment.until
                        .compareTo(effectiveFrom) < 0), "分段范围重叠");
                lastEnd = segment.until;
            }
        }

        private void validateFrequency() {
            require(version > 0 && rule != null && interval >= 1 && interval <= 366, "版本或间隔无效");
            require(startMinute >= 0 && startMinute < 1440 && durationMinutes >= 0
                    && durationMinutes <= CalendarEditing.MAX_MINUTES, "时间无效");
            require(count >= 0 && count <= 100000 && weekdays > 0 && weekdays <= 127, "次数或星期无效");
        }

        private void validateOptions() {
            require(nth >= 1 && nth <= 5 && weekday >= 1 && weekday <= 7, "月度星期规则无效");
            require(rotation != null && rotation.matches("[01]{1,366}") && rotation.contains("1"), "轮班使用 1 工作 / 0 休息");
            require(reminderMinutes >= 0 && reminderMinutes <= 1440 && reminderTargets >= 0 && reminderTargets <= 3,
                    "提醒无效");
            require(leaveWeek >= -1 && leaveMonth >= -1, "请假额度无效");
        }

        private void validateOverrides() {
            require(steps != null && steps.size() <= 30, "步骤过多");
            for (String s : steps) {
                require(s != null && s.length() <= 160, "步骤无效");
            }
            for (Map<String, Boolean> m : Arrays.asList(company, confirmed, official)) {
                require(m != null && m.size() <= 4000, "排班数据过大");
                m.forEach((k, v) -> {
                    LocalDate.parse(k);
                    require(v != null, "排班值无效");
                });
            }
            require(exceptions != null && exceptions.size() <= 4000, "例外过多");
            exceptions.forEach(this::validateException);
        }

        private void validateException(String k, ExceptionDay v) {
            LocalDate.parse(k);
            require(v != null, "例外无效");
            require(v.title == null || (!v.title.trim().isEmpty() && v.title.length() <= 160), "例外名称无效");
            require(v.category == null || (!v.category.trim().isEmpty() && v.category.length() <= 80), "例外大类无效");
            require(v.daySpan == null || (v.daySpan >= 1 && v.daySpan <= 366), "例外全天范围无效");
            require(dateOnly || v.daySpan == null || v.daySpan == 1, "具体时间例外不使用全天范围");
            if (v.scheduledDate != null) {
                LocalDate.parse(v.scheduledDate);
            }
            require(v.minute >= 0 && v.minute < 1440 && v.duration >= 0
                    && v.duration <= CalendarEditing.MAX_MINUTES, "例外无效");
        }
    }

    public static class ExceptionDay {

        public boolean skip;

        public int minute;

        public int duration;

        public String title;

        public String category;

        public String scheduledDate;

        public Integer daySpan;
    }

    public static class Instance {

        public Kind kind = Kind.EXECUTION;

        public boolean dateOnly;

        public int daySpan = 1;

        public boolean timed = true;

        public String id;

        public String planId;

        public String date;

        public String title;

        public String category;

        public String zone;

        public long planVersion;

        public long start;

        public long end;

        public boolean cancelled;

        public boolean disputed;

        public int reminderMinutes;

        public int reminderTargets;

        public List<String> steps = new ArrayList<>();
    }

    public static class Event {

        public String id = UUID.randomUUID().toString();

        public String instanceId;

        public String deviceId;

        public long logical;

        public long confirmedAt;

        public Long actualAt;

        public Action action;

        public Instance snapshot;

        public boolean correction;

        public boolean completedEarlier;

        public int continueMinutes;

        public void validate() {
            validateIdentity();
            validateCompletion();
            if (snapshot != null) {
                validateSnapshot();
            }
        }

        private void validateIdentity() {
            require(id != null && id.matches("[A-Za-z0-9_-]{1,80}") && instanceId != null && instanceId
                    .length() <= 150, "记录 ID 无效");
            require(deviceId != null && deviceId.matches("[A-Za-z0-9_-]{1,80}") && logical > 0 && confirmedAt > 0
                    && action != null, "记录无效");
            require(actualAt == null || (actualAt > 0 && actualAt <= confirmedAt), "实际时间不能晚于确认时间");
        }

        private void validateCompletion() {
            require(action != Action.REOPEN || snapshot != null, "待办操作需要快照");
            require(snapshot == null || snapshot.kind != Kind.TODO || (!correction && !completedEarlier),
                    "待办使用完成或重新打开操作");
            require(action != Action.CONTINUE || Arrays.asList(5, 10, 15, 30).contains(continueMinutes), "继续时长无效");
            require(!completedEarlier || (action == Action.COMPLETE && actualAt == null), "补录不可伪造时间");
            require(action != Action.COMPLETE || completedEarlier || actualAt != null, "完成时间缺失");
        }

        private void validateSnapshot() {
            require(instanceId.equals(snapshot.id) && snapshot.id.equals(snapshot.planId + ":" + snapshot.date),
                    "执行快照无效");
            Kinds.validateInstance(snapshot);
            Kinds.validateAction(snapshot, action);
            require(!snapshot.cancelled, "执行快照无效");
            require(snapshot.title != null && snapshot.title.length() <= 160
                    && snapshot.category != null && snapshot.category.length() <= 80, "执行快照无效");
            validateSnapshotReminders();
            LocalDate.parse(snapshot.date);
            ZoneId.of(snapshot.zone);
        }

        private void validateSnapshotReminders() {
            require(snapshot.steps != null && snapshot.steps.size() <= 30, "执行快照无效");
            require(snapshot.reminderMinutes >= 0 && snapshot.reminderMinutes <= 1440
                    && snapshot.reminderTargets >= 0 && snapshot.reminderTargets <= 3, "执行快照无效");
        }
    }

    public static class Settings {

        public int dateOnlyReminderMinute = 9 * 60;

        public int leaveWeek = 1;

        public int leaveMonth = 4;

        public int quietStart = 22 * 60;

        public int quietEnd = 7 * 60;

        public int reminderMinutes = 0;

        public int reminderTargets = 1;

        public boolean phoneRemindersEnabled = true;

        public boolean watchRemindersEnabled = false;

        public Map<String, Integer> categoryWeek = new TreeMap<>();

        public Map<String, Integer> categoryMonth = new TreeMap<>();
        public void validate() {
            require(dateOnlyReminderMinute >= 0 && dateOnlyReminderMinute < 1440, "默认提醒时间无效");
            require(leaveWeek >= 0 && leaveMonth >= 0, "额度无效");
            require(quietStart >= 0 && quietStart < 1440 && quietEnd >= 0 && quietEnd < 1440, "静音设置无效");
            require(reminderMinutes >= 0 && reminderMinutes <= 1440
                    && reminderTargets >= 0 && reminderTargets <= 3, "提醒设置无效");
            validateCategoryQuotas(categoryWeek);
            validateCategoryQuotas(categoryMonth);
        }

        private static void validateCategoryQuotas(Map<String, Integer> values) {
            require(values != null && values.size() <= 1000, "大类额度数量无效");
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                String name = entry.getKey();
                require(name != null && !name.trim().isEmpty() && name.length() <= 80, "大类名称无效");
                require(entry.getValue() != null && entry.getValue() >= 0, "大类额度无效");
            }
        }
    }

    public static class Suggestion {

        public String planId;

        public String kind;

        public String oldValue;

        public String proposedValue;

        public String evidence;

        public String futureFrom;

        public int samples;

        public int matching;

        // never applied by the analysis engine
        public boolean formal;

        // never applied by the analysis engine
        public boolean accepted;
    }

    public static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
