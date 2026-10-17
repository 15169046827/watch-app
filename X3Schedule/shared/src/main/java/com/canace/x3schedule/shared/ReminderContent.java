package com.canace.x3schedule.shared;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Reminders.Purpose;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded notification content, coalescing overdue phases of the same instance. */
final class ReminderContent {
    private static final int MAX_LINES = 6;

    private final Map<String, String> lines = new LinkedHashMap<>();

    private String firstId;

    private String firstTitle;

    void add(Instance instance, Purpose purpose, long now) {
        if (firstId == null) {
            firstId = instance.id;
            firstTitle = instance.title;
        }
        String reason;
        if (purpose == Purpose.RECOVERY_CONFLICT) {
            reason = "未来七天备用补救存在冲突，请在手机处理";
        } else if (purpose == Purpose.RECOVERY_START) {
            reason = "正式补救时段开始";
        } else if (purpose == Purpose.RECOVERY_END) {
            reason = "补救结束，请反馈累计成果";
        } else if (purpose != Purpose.START) {
            reason = purpose == Purpose.CONFIRMATION ? "延后时间已到，请确认完成情况" : "请确认完成情况";
        } else if (instance.kind == Kind.TODO) {
            reason = "待办到期提醒";
        } else if (instance.kind == Kind.REMINDER) {
            reason = "日程时间提醒";
        } else {
            reason = now < instance.start ? "即将开始" : "到时间开始";
        }
        lines.put(instance.id, instance.title + " · " + reason);
    }

    int size() {
        return lines.size();
    }

    String instanceId() {
        return size() == 1 ? firstId : null;
    }

    String title() {
        return size() == 1 ? firstTitle : firstTitle + " 等 " + size() + " 项日程";
    }

    String text() {
        StringBuilder result = new StringBuilder();
        int shown = 0;
        for (String line : lines.values()) {
            if (shown == MAX_LINES) {
                break;
            }
            if (shown++ > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        if (size() > MAX_LINES) {
            result.append("\n另有 ").append(size() - MAX_LINES).append(" 项，点击查看");
        }
        return result.toString();
    }
}
