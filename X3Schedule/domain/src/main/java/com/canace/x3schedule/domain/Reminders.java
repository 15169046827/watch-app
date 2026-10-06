package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public final class Reminders {

    private Reminders() {
    }

    public static class Due {

        public String key;

        public String instanceId;

        public long at;

        public Due(String k, String id, long t) {
            key = k;
            instanceId = id;
            at = t;
        }
    }

    public static List<Due> candidates(Instance i, Event e, boolean phone, Settings settings) {
        List<Due> out = new ArrayList<>();
        if (!enabled(i, e, phone, settings)) {
            return out;
        }
        String key = i.kind == Kind.TODO ? ":deadline:" + i.start : ":start";
        long at = i.dateOnly ? DayPrecision.reminderAt(i, settings.dateOnlyReminderMinute)
                : i.start - i.reminderMinutes * 60000L;
        out.add(new Due(i.id + key, i.id, at));
        if (i.kind != Kind.EXECUTION) {
            shiftQuietHours(out, ZoneId.of(i.zone), settings);
            return out;
        }
        if (e != null && e.action == Action.CONTINUE) {
            out.add(new Due(i.id + ":continue:" + e.id, i.id, e.confirmedAt + e.continueMinutes * 60000L));
        } else {
            for (int min : new int[] { 0, 15, 60 }) {
                out.add(new Due(i.id + ":end:" + min, i.id, i.end + min * 60000L));
            }
        }
        shiftQuietHours(out, ZoneId.of(i.zone), settings);
        return out;
    }
    private static boolean enabled(Instance instance, Event event, boolean phone, Settings settings) {
        boolean endpointEnabled = phone ? settings.phoneRemindersEnabled : settings.watchRemindersEnabled;
        return endpointEnabled && instance.timed && !instance.cancelled
                && (instance.reminderTargets & (phone ? 1 : 2)) != 0
                && !terminal(event);
    }

    private static boolean terminal(Event event) {
        return event != null && (event.action == Action.COMPLETE
                || event.action == Action.LEAVE || event.action == Action.INCOMPLETE);
    }

    /**
     * Recheck quiet hours at delivery, while retaining the original catch-up expiry and notification key.
     */
    public static long deliveryAt(Due due, long now, String zone, Settings settings) {
        if (due.at < now - 86400000L) {
            return Long.MAX_VALUE;
        }
        return outsideQuietHours(Math.max(due.at, now), ZoneId.of(zone), settings);
    }

    private static void shiftQuietHours(List<Due> out, ZoneId zone, Settings settings) {
        for (Due due : out) {
            due.at = outsideQuietHours(due.at, zone, settings);
        }
    }

    private static long outsideQuietHours(long at, ZoneId zone, Settings settings) {
        ZonedDateTime time = Instant.ofEpochMilli(at).atZone(zone);
        int minute = time.getHour() * 60 + time.getMinute();
        if (!Execution.quiet(minute, settings.quietStart, settings.quietEnd)) {
            return at;
        }
        LocalDate day = time.toLocalDate();
        if (settings.quietStart > settings.quietEnd && minute >= settings.quietStart) {
            day = day.plusDays(1);
        }
        return day.atStartOfDay().plusMinutes(settings.quietEnd).atZone(zone).toInstant().toEpochMilli();
    }
}
