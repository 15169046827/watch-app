package com.canace.x3schedule.shared;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.RecoveryConflicts;
import com.canace.x3schedule.domain.Reminders;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Automatic rolling conflict summaries stay inexact; acknowledging never disables enable-time checks. */
final class RecoveryConflictReminders {
    private RecoveryConflictReminders() {
    }

    static Map<String, List<Reminders.Due>> candidates(List<Instance> items, List<Event> events,
            long now, Settings settings) {
        Map<String, List<Reminders.Due>> result = new TreeMap<>();
        if (!settings.phoneRemindersEnabled) {
            return result;
        }
        for (RecoveryConflicts.Conflict conflict : RecoveryConflicts.scan(items, events, now)) {
            if (conflict.acknowledged || conflict.blocked) {
                continue;
            }
            LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of(conflict.item.zone)).toLocalDate();
            List<Reminders.Due> due = result.computeIfAbsent(conflict.item.id, key -> new ArrayList<>());
            for (int offset = 0; offset <= 1; offset++) {
                LocalDate date = today.plusDays(offset);
                long at = Progress.boundary(date, conflict.item.zone, settings.dateOnlyReminderMinute);
                if (at < conflict.start) {
                    due.add(new Reminders.Due("recovery-conflict:" + date + ":" + conflict.key,
                            conflict.item.id, at, Reminders.Purpose.RECOVERY_CONFLICT));
                }
            }
        }
        return result;
    }
}
