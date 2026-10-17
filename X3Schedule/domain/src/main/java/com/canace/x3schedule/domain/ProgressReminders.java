package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Versioned progress reminders share the existing phone permission and quiet-hour pipeline. */
public final class ProgressReminders {
    private ProgressReminders() {
    }

    public static List<Reminders.Due> candidates(Instance item, Collection<Event> events,
            boolean phone, Settings settings) {
        List<Reminders.Due> result = new ArrayList<>();
        if (!enabled(item, phone, settings)) {
            return result;
        }
        Progress.Summary state = Progress.summarize(item, events);
        if (state.exempt()) {
            return result;
        }
        if (state.original == null) {
            long start = start(item, settings);
            result.add(new Reminders.Due(item.id + ":progress-start", item.id, start, Reminders.Purpose.START));
            for (int minute : new int[] { 0, 15, 60 }) {
                result.add(new Reminders.Due(item.id + ":progress-end:" + minute, item.id, item.end + minute * 60000L,
                        minute == 0 ? Reminders.Purpose.END : Reminders.Purpose.RETRY));
            }
        }
        if (state.enabled && !state.recoveryLeave && !state.scheduledActed && !state.recoveryMiss) {
            result.add(new Reminders.Due(item.id + ":recovery-start:" + state.recoveryStart, item.id,
                    state.recoveryStart, Reminders.Purpose.RECOVERY_START));
            result.add(new Reminders.Due(item.id + ":recovery-end:" + state.recoveryEnd, item.id,
                    state.recoveryEnd, Reminders.Purpose.RECOVERY_END));
        }
        Reminders.shiftQuietHours(result, ZoneId.of(item.zone), settings);
        return result;
    }

    private static long start(Instance item, Settings settings) {
        if (!item.progress.untimed) {
            return item.start - item.reminderMinutes * 60000L;
        }
        int minute = item.progress.remindMinute < 0 ? settings.dateOnlyReminderMinute : item.progress.remindMinute;
        return Progress.taskMinute(LocalDate.parse(item.taskDay), item.zone, item.progress.boundaryMinute, minute);
    }

    private static boolean enabled(Instance item, boolean phone, Settings settings) {
        return phone && settings.phoneRemindersEnabled && (item.reminderTargets & 1) != 0 && !item.cancelled;
    }
}
