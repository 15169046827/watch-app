package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Event;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Concurrent offline leave claims consume frozen quotas deterministically, without deleting rejected facts. */
public final class ProgressLeaves {
    private ProgressLeaves() {
    }

    public static Set<String> accepted(Collection<Event> events) {
        events = ProgressHistory.effective(events);
        List<Event> claims = new ArrayList<>();
        Set<String> missed = new HashSet<>();
        for (Event event : events) {
            Progress.Entry entry = event.progress;
            if (entry == null || event.snapshot == null || event.snapshot.progress == null) {
                continue;
            }
            if (entry.op == Progress.Op.LEAVE) {
                claims.add(event);
            } else if (entry.op == Progress.Op.RESULT && !entry.acted && !event.snapshot.progress.untimed) {
                missed.add(event.instanceId + ":" + entry.session);
            }
        }
        claims.sort(Comparator.comparingLong((Event e) -> e.confirmedAt).thenComparing(e -> e.deviceId)
                .thenComparing(e -> e.id));
        Set<String> accepted = new HashSet<>();
        Set<String> consumed = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Event claim : claims) {
            String session = claim.instanceId + ":" + claim.progress.session;
            if (!missed.contains(session) && !consumed.contains(session) && withinQuota(claim, counts)) {
                accepted.add(claim.id);
                consumed.add(session);
            }
        }
        return accepted;
    }

    private static boolean withinQuota(Event event, Map<String, Integer> counts) {
        Progress.Options options = event.snapshot.progress;
        LocalDate day = LocalDate.parse(event.snapshot.taskDay);
        String week = "week:" + day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                + ":" + options.leaveWeekScope;
        String month = "month:" + YearMonth.from(day) + ":" + options.leaveMonthScope;
        if (counts.getOrDefault(week, 0) >= options.leaveWeek
                || counts.getOrDefault(month, 0) >= options.leaveMonth) {
            return false;
        }
        counts.merge(week, 1, Integer::sum);
        counts.merge(month, 1, Integer::sum);
        return true;
    }
}
