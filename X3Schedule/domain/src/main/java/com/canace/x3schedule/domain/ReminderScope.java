package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;

/** Legacy targets remain serializable, but never silently opt users into phone notifications. */
public final class ReminderScope {
    private ReminderScope() {
    }

    public static boolean pending(int targets) {
        return targets == 2;
    }

    public static boolean pending(Plan plan, Settings settings) {
        return !plan.deleted && pending(plan.inheritReminders ? settings.reminderTargets : plan.reminderTargets);
    }

    public static void requireSave(Plan draft, Plan prior, Settings settings) {
        Models.require(!pending(draft, settings) || prior != null && pending(prior, settings),
                "手表本地提醒已停用，请选择手机提醒或不提醒；使用默认设置时请先调整默认提醒端。");
    }
}
