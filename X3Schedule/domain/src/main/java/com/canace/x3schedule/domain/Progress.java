package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned, append-only achievement facts. Missing feedback is never an assertion of no action. */
public final class Progress {
    private Progress() {
    }

    public enum Grade { NONE, MINIMUM, BASIC, PERFECT }
    public enum Session { ORIGINAL, SCHEDULED, MANUAL }
    public enum Op {
        RESULT, ENABLE, CANCEL, RESCHEDULE, LEAVE, YIELD, REVOKE_YIELD, ACK_CONFLICT, STANDBY, BLOCK_STANDBY, CORRECT
    }

    public static class Options {
        public int version = 1;
        public boolean scored = true;
        public boolean untimed;
        public boolean allowYield;
        public int boundaryMinute = 240;
        public String perfect = "";
        public String basic = "";
        public String minimum = "";
        public int recoveryMinute = -1;
        public int recoveryDayOffset;
        public int recoveryDuration = 30;
        public int remindMinute = -1;
        public int checkinMinute = -1;
        public int leaveWeek = 1;
        public int leaveMonth = 4;
        public String leaveWeekScope = "global";
        public String leaveMonthScope = "global";

        public boolean graded() {
            return !perfect.isEmpty();
        }

        public void validate() {
            require(version == 1, "不支持的评分版本");
            validateStandards();
            validateTime();
            require(leaveWeek >= 0 && leaveMonth >= 0 && leaveWeekScope != null && leaveMonthScope != null,
                    "请假规则无效");
        }

        private void validateStandards() {
            require(perfect != null && basic != null && minimum != null, "完成标准缺失");
            require(perfect.length() <= 500 && basic.length() <= 500 && minimum.length() <= 500, "完成标准最多500字");
            require((perfect.isEmpty() && basic.isEmpty() && minimum.isEmpty())
                    || (!perfect.trim().isEmpty() && !basic.trim().isEmpty()
                    && !minimum.trim().isEmpty()), "请填写全部三档标准，或全部留空使用完成/未完成");
        }

        private void validateTime() {
            require(boundaryMinute >= 0 && boundaryMinute < 1440, "任务日起点无效");
            require(recoveryMinute >= -1 && recoveryMinute < 1440, "补救时间无效");
            require(remindMinute >= -1 && remindMinute < 1440
                    && checkinMinute >= -1 && checkinMinute < 1440, "不定时提醒或反馈时刻无效");
            require(recoveryDuration >= 1 && recoveryDuration <= 1440
                    && recoveryDayOffset >= 0 && recoveryDayOffset <= 1, "补救时长或日期无效");
            require(!untimed || recoveryMinute == -1, "不定时任务不设置正式补救时段");
        }
    }

    public static class Entry {
        public Op op;
        public Session session = Session.ORIGINAL;
        public Grade grade;
        public boolean acted = true;
        public long start;
        public long end;
        public List<String> yielded = new ArrayList<>();
        public String conflictKey = "";
        public String replacesId = "";
        public String recoveryParent = "";
    }

    public static class Summary {
        public Grade grade;
        public Grade original;
        public boolean binary;
        public boolean originalMiss;
        public boolean recoveryMiss;
        public boolean originalLeave;
        public boolean recoveryLeave;
        public boolean yielded;
        public boolean enabled;
        public boolean scheduledActed;
        public Grade scheduledGrade;
        public int enabledCount;
        public int scheduledUpgrades;
        public int manualActions;
        public long recoveryStart;
        public long recoveryEnd;
        public long standbyStart;
        public long standbyEnd;
        public String consentKey = "";
        public String recoveryHead = "";
        public boolean recoveryConflict;
        public long earliestRecoveryStart;
        public final Set<String> yieldedIds = new HashSet<>();
        public final Set<String> acknowledged = new HashSet<>();
        public final Set<String> blocked = new HashSet<>();

        public final Set<String> yieldSources = new HashSet<>();

        public int deviations() {
            return (originalMiss ? 1 : 0) + (recoveryMiss ? 1 : 0);
        }

        public boolean exempt() {
            return originalLeave || yielded;
        }
    }

    public static LocalDate day(long time, String zone, int boundary) {
        java.time.ZonedDateTime local = Instant.ofEpochMilli(time).atZone(ZoneId.of(zone));
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().toSecondOfDay() / 60 < boundary ? date.minusDays(1) : date;
    }

    public static long boundary(LocalDate date, String zone, int minute) {
        return date.atStartOfDay().plusMinutes(minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    public static boolean occupiesTime(Instance item) {
        return item.timed && !item.cancelled && (item.progress == null || !item.progress.untimed);
    }

    public static void bind(Instance instance, Options options) {
        if (options == null) {
            return;
        }
        instance.progress = Wire.copy(options, Options.class);
        if (options.untimed) {
            LocalDate date = LocalDate.parse(instance.date);
            instance.start = boundary(date, instance.zone, options.boundaryMinute);
            instance.end = boundary(date.plusDays(1), instance.zone, options.boundaryMinute);
            if (options.checkinMinute >= 0) {
                instance.end = options.checkinMinute == options.boundaryMinute
                        ? boundary(date.plusDays(1), instance.zone, options.checkinMinute)
                        : taskMinute(date, instance.zone, options.boundaryMinute, options.checkinMinute);
            }
        }
        LocalDate date = day(instance.start, instance.zone, options.boundaryMinute);
        instance.taskDay = date.toString();
        instance.taskDayEnd = boundary(date.plusDays(1), instance.zone, options.boundaryMinute);
    }

    public static long taskMinute(LocalDate date, String zone, int boundary, int minute) {
        return boundary(minute < boundary ? date.plusDays(1) : date, zone, minute);
    }

    public static void validateInstance(Instance instance) {
        if (instance.progress == null) {
            return;
        }
        instance.progress.validate();
        require(instance.kind == Models.Kind.EXECUTION && instance.timed && !instance.dateOnly,
                "等级实例必须为执行任务");
        require(instance.taskDay != null && instance.taskDay.equals(day(instance.start, instance.zone,
                instance.progress.boundaryMinute).toString()), "任务日归属无效");
        require(instance.taskDayEnd == boundary(LocalDate.parse(instance.taskDay).plusDays(1),
                instance.zone, instance.progress.boundaryMinute), "任务日边界无效");
    }

    public static void validate(Event event) {
        require((event.action == Models.Action.PROGRESS) == (event.progress != null), "反馈版本与动作不匹配");
        if (event.snapshot != null && event.snapshot.progress != null) {
            validateInstance(event.snapshot);
            require(event.progress != null, "等级任务请使用等级反馈");
        }
        if (event.progress == null) {
            return;
        }
        require(event.snapshot != null && event.snapshot.progress != null, "反馈需要原始快照");
        require(event.correction == (event.progress.op == Op.CORRECT), "更正标记与动作不匹配");
        validateEntry(event);
    }

    private static void validateEntry(Event event) {
        Entry entry = event.progress;
        require(entry.op != null && entry.session != null && entry.yielded != null
                && entry.conflictKey != null && entry.conflictKey.length() <= 64, "反馈字段无效");
        require(entry.yielded.size() <= 1000, "让位数据过多");
        require(entry.replacesId != null && entry.replacesId.length() <= 80, "更正引用无效");
        require(entry.recoveryParent != null && entry.recoveryParent.length() <= 80, "补救因果引用无效");
        for (String id : entry.yielded) {
            require(id != null && id.length() <= 150, "让位实例无效");
        }
        validateResult(event);
        validateWindow(event);
    }

    private static void validateWindow(Event event) {
        Entry entry = event.progress;
        if (entry.op == Op.ENABLE || entry.op == Op.RESCHEDULE || entry.op == Op.STANDBY) {
            require(!event.snapshot.progress.untimed && entry.start >= event.snapshot.end
                    && entry.start > event.confirmedAt && entry.end > entry.start
                    && entry.end <= event.snapshot.taskDayEnd, "补救必须在未来且属于原任务日");
            require(entry.end - entry.start <= 86400000L, "补救时长过长");
        }
    }

    private static void validateResult(Event event) {
        Entry entry = event.progress;
        if (entry.op != Op.RESULT && entry.op != Op.CORRECT) {
            require(entry.grade == null, "非反馈操作不能指定成果");
            return;
        }
        require(entry.grade != null, "请选择完成等级");
        require(event.snapshot.progress.graded() || entry.grade == Grade.PERFECT || entry.grade == Grade.NONE,
                "尚未定义等级标准，仅支持完成/未完成");
        require(entry.acted || entry.session != Session.MANUAL || entry.op == Op.CORRECT,
                "自行补做须如实记录行动");
        boolean binaryAction = !event.snapshot.progress.graded() && entry.grade == Grade.NONE && entry.acted;
        require(entry.session != Session.ORIGINAL || binaryAction || entry.acted == (entry.grade != Grade.NONE),
                "原时段行动与成果矛盾");
        require(!entry.acted || entry.grade != Grade.NONE || binaryAction,
                "有行动请至少选择最低行动；未进行不能代表补救行动");
        require(entry.op != Op.CORRECT || !entry.replacesId.isEmpty(), "请选择要更正的原始记录");
    }

    public static Summary summarize(Instance instance, Collection<Event> events) {
        events = ProgressHistory.effective(events);
        Summary summary = new Summary();
        summary.binary = !instance.progress.graded();
        List<Event> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> leaves = ProgressLeaves.accepted(events);
        for (Event event : events) {
            if (instance.id.equals(event.instanceId) && event.progress != null && seen.add(event.id)) {
                if (event.progress.op != Op.LEAVE || leaves.contains(event.id)) {
                    ordered.add(event);
                }
            }
        }
        ordered.sort(Comparator.comparingLong((Event e) -> e.logical).thenComparing(e -> e.deviceId)
                .thenComparing(e -> e.id));
        for (Event event : ordered) {
            trackRecovery(summary, event);
            apply(summary, event.progress, instance.progress.untimed);
        }
        return summary;
    }

    private static void trackRecovery(Summary state, Event event) {
        Entry entry = event.progress;
        if (entry.session == Session.SCHEDULED && (entry.op == Op.RESULT || entry.op == Op.LEAVE)
                && entry.recoveryParent.equals(state.recoveryHead)) {
            state.enabled |= state.recoveryConflict;
            state.recoveryConflict = false;
        }
        if (entry.op != Op.ENABLE && entry.op != Op.RESCHEDULE && entry.op != Op.CANCEL) {
            return;
        }
        boolean stale = !entry.recoveryParent.equals(state.recoveryHead);
        state.recoveryConflict = stale;
        state.recoveryHead = event.id;
        if (!stale) {
            state.earliestRecoveryStart = entry.start;
        } else if (entry.start > 0) {
            state.earliestRecoveryStart = state.earliestRecoveryStart == 0 ? entry.start
                    : Math.min(state.earliestRecoveryStart, entry.start);
        }
    }

    public static boolean freezes(Event event) {
        return event.progress == null || event.snapshot == null || event.confirmedAt >= event.snapshot.start;
    }

    public static Map<String, List<Event>> index(Collection<Event> events) {
        events = ProgressHistory.effective(events);
        Map<String, List<Event>> result = new HashMap<>();
        Set<String> leaves = ProgressLeaves.accepted(events);
        for (Event event : events) {
            if (event.progress != null && (event.progress.op != Op.LEAVE || leaves.contains(event.id))) {
                result.computeIfAbsent(event.instanceId, key -> new ArrayList<>()).add(event);
            }
        }
        return result;
    }

    public static Map<String, Summary> summaries(Collection<Instance> items, Collection<Event> events) {
        Map<String, List<Event>> indexed = index(events);
        Map<String, Summary> result = new HashMap<>();
        for (Instance item : items) {
            if (item.progress != null) {
                result.put(item.id, summarize(item, indexed.getOrDefault(item.id, java.util.Collections.emptyList())));
            }
        }
        return result;
    }

    private static void apply(Summary summary, Entry entry, boolean untimed) {
        switch (entry.op) {
            case RESULT:
                result(summary, entry, untimed);
                break;
            case ENABLE:
                summary.enabled = true;
                summary.enabledCount = 1;
                window(summary, entry);
                break;
            case RESCHEDULE:
                summary.enabled = true;
                window(summary, entry);
                break;
            case CANCEL:
                summary.enabled = false;
                break;
            case LEAVE:
                if (entry.session == Session.SCHEDULED) {
                    summary.recoveryLeave = !summary.recoveryMiss;
                } else {
                    summary.originalLeave = !summary.originalMiss;
                }
                break;
            case YIELD:
                summary.yieldSources.add(entry.conflictKey);
                summary.yielded = true;
                break;
            case REVOKE_YIELD:
                summary.yieldSources.remove(entry.conflictKey);
                summary.yielded = !summary.yieldSources.isEmpty();
                break;
            case ACK_CONFLICT:
                summary.acknowledged.add(entry.conflictKey);
                break;
            case BLOCK_STANDBY:
                summary.blocked.add(entry.conflictKey);
                break;
            case STANDBY:
                summary.standbyStart = entry.start;
                summary.standbyEnd = entry.end;
                break;
            default:
                throw new IllegalArgumentException("未知反馈操作");
        }
    }

    private static void window(Summary summary, Entry entry) {
        summary.recoveryStart = entry.start;
        summary.recoveryEnd = entry.end;
        summary.yieldedIds.clear();
        summary.yieldedIds.addAll(entry.yielded);
        summary.consentKey = entry.conflictKey;
    }

    private static void result(Summary summary, Entry entry, boolean untimed) {
        Grade before = summary.grade;
        if (entry.session == Session.ORIGINAL) {
            summary.original = highest(summary.original, entry.grade);
            summary.originalMiss |= !untimed && !entry.acted;
        } else if (entry.session == Session.SCHEDULED) {
            summary.recoveryMiss |= !entry.acted;
            summary.scheduledActed |= entry.acted;
            if (entry.acted) {
                summary.scheduledGrade = highest(summary.scheduledGrade, entry.grade);
            }
            if (entry.acted && (before == null || entry.grade.ordinal() > before.ordinal())) {
                summary.scheduledUpgrades++;
            }
        } else {
            summary.manualActions += entry.acted ? 1 : 0;
        }
        if (entry.acted || entry.session != Session.SCHEDULED) {
            summary.grade = highest(summary.grade, entry.grade);
        }
    }

    private static Grade highest(Grade first, Grade second) {
        return first == null || second.ordinal() > first.ordinal() ? second : first;
    }

    public static long expiry(Instance instance) {
        return boundary(LocalDate.parse(instance.taskDay).plusDays(4), instance.zone,
                instance.progress.boundaryMinute);
    }

    public static void requireRecord(Instance instance, Collection<Event> events, Entry entry, long now) {
        require(instance.progress != null && !instance.cancelled, "此任务不支持等级反馈");
        require(now < expiry(instance), "反馈期限已结束：可补录过去三个任务日");
        require(now >= instance.start || preparation(entry.op), "任务尚未开始");
        Summary current = summarize(instance, events);
        if (entry.op == Op.RESULT) {
            requireResult(instance, current, entry, now);
        } else if (entry.op == Op.CANCEL || entry.op == Op.RESCHEDULE) {
            require((current.enabled || current.recoveryConflict) && now < current.earliestRecoveryStart,
                    "补救开始后不可取消或改期");
        } else if (entry.op == Op.ENABLE) {
            requireEnable(instance, current, now);
        } else if (entry.op == Op.LEAVE && entry.session == Session.SCHEDULED) {
            require(current.enabled && !current.recoveryMiss, "没有可请假的补救承诺，已确认失约不能撤销");
        } else if (entry.op == Op.STANDBY || entry.op == Op.BLOCK_STANDBY) {
            require(!current.enabled, "已启用的补救承诺不可失效，请使用取消或重排");
        }
    }

    private static boolean preparation(Op op) {
        return op == Op.ACK_CONFLICT || op == Op.YIELD || op == Op.STANDBY || op == Op.BLOCK_STANDBY;
    }

    private static void requireEnable(Instance instance, Summary current, long now) {
        require(!instance.progress.untimed && !current.enabled && !current.scheduledActed
                && !current.recoveryMiss && !current.recoveryConflict, "每个任务实例最多一个正式补救时段，请先处理离线冲突");
        require(current.original != null && current.grade != Grade.PERFECT && now < instance.taskDayEnd,
                "请先反馈原时段成果；完整完成或已过任务日不能启用补救");
        require(!current.exempt(), "已豁免的任务不启用补救");
    }

    private static void requireResult(Instance instance, Summary current, Entry entry, long now) {
        require(entry.grade != null, "请选择完成等级");
        require(!current.exempt(), "本次已有有效请假或让位豁免，保留该事实");
        if (entry.session == Session.ORIGINAL) {
            require(current.original == null, "原时段已记录，后续成果请选择自行补做");
        } else if (entry.session == Session.SCHEDULED) {
            require((current.enabled || current.recoveryConflict) && now >= current.recoveryEnd
                    && !current.recoveryLeave,
                    "请在已启用补救结束后反馈");
            require(!current.scheduledActed && !current.recoveryMiss,
                    "正式补救已反馈，继续提高成果请使用自行补做");
        } else {
            require(current.original != null, "补录请先如实填写原时段行动");
            require(now < instance.taskDayEnd || current.manualActions == 0, "历史补录仅允许补充一次最终成果");
        }
        require(!entry.acted || current.grade == null || entry.grade.ordinal() >= current.grade.ordinal(),
                "累计成果不能低于已有成果；未补救请选择完全没有补救");
    }

    public static int score(Grade grade, int deviations) {
        require(grade != null && deviations >= 0 && deviations <= 2, "评分参数无效");
        int[][] matrix = { { 0, 0, 0 }, { 30, 15, 5 }, { 60, 45, 30 }, { 100, 80, 60 } };
        return matrix[grade.ordinal()][deviations];
    }

    public static Models.State state(Instance item, Summary summary, long now) {
        if (summary.exempt()) {
            return Models.State.LEAVE;
        }
        Models.State recovery = recoveryState(summary, now);
        if (recovery != null) {
            return recovery;
        }
        if (summary.grade != null) {
            return summary.grade == Grade.PERFECT ? Models.State.ON_TIME : Models.State.INCOMPLETE;
        }
        return now < item.start ? Models.State.UPCOMING
                : now < item.end ? Models.State.RUNNING : Models.State.PENDING;
    }

    private static Models.State recoveryState(Summary summary, long now) {
        if (summary.recoveryConflict) {
            return Models.State.PENDING;
        }
        if (!summary.enabled || summary.recoveryLeave || summary.scheduledActed || summary.recoveryMiss) {
            return null;
        }
        if (now >= summary.recoveryEnd) {
            return Models.State.PENDING;
        }
        return now >= summary.recoveryStart ? Models.State.RUNNING : null;
    }

    public static String label(Grade grade) {
        if (grade == null) {
            return "○ 待确认";
        }
        switch (grade) {
            case PERFECT:
                return "✓ 完整完成";
            case BASIC:
                return "◐ 基本完成";
            case MINIMUM:
                return "● 最低行动";
            default:
                return "— 未进行";
        }
    }

    public static String describe(Summary summary) {
        String text = summary.exempt() ? "请假/让位豁免" : achievement(summary);
        if (summary.enabledCount > 0 || summary.manualActions > 0) {
            text += " · ↻ 补救";
        }
        return text + (summary.deviations() > 0 ? " · 偏离" + summary.deviations() + "次" : "");
    }

    public static String achievement(Summary summary) {
        return summary.binary && summary.grade == Grade.NONE ? "— 未完成" : label(summary.grade);
    }
}
