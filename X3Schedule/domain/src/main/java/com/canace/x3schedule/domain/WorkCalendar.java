package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * National calendars verified against State Council notices; no prediction for unpublished years.
 */
public final class WorkCalendar {

    private static final Map<String, Boolean> DAYS = new TreeMap<>();

    static {
        for (int year : new int[] { 2025, 2026 }) {
            for (LocalDate d = LocalDate.of(year, 1, 1); d.getYear() == year; d = d.plusDays(1)) {
                DAYS.put(d.toString(), d.getDayOfWeek().getValue() <= 5);
            }
        }
        rest("2025-01-01", "2025-01-01");
        rest("2025-01-28", "2025-02-04");
        rest("2025-04-04", "2025-04-06");
        rest("2025-05-01", "2025-05-05");
        rest("2025-05-31", "2025-06-02");
        rest("2025-10-01", "2025-10-08");
        work("2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11");
        rest("2026-01-01", "2026-01-03");
        rest("2026-02-15", "2026-02-23");
        rest("2026-04-04", "2026-04-06");
        rest("2026-05-01", "2026-05-05");
        rest("2026-06-19", "2026-06-21");
        rest("2026-09-25", "2026-09-27");
        rest("2026-10-01", "2026-10-07");
        work("2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10");
    }

    private WorkCalendar() {
    }

    private static void rest(String first, String last) {
        for (LocalDate d = LocalDate.parse(first); !d.isAfter(LocalDate.parse(last)); d = d.plusDays(1)) {
            DAYS.put(d.toString(), false);
        }
    }

    private static void work(String... dates) {
        for (String d : dates) {
            DAYS.put(d, true);
        }
    }

    public static Boolean official(Models.Plan p, LocalDate date) {
        return p.official.containsKey(date.toString()) ? p.official.get(date.toString()) : DAYS.get(date.toString());
    }

    public static boolean disputed(Models.Plan p, LocalDate date) {
        Boolean official = official(p, date);
        Boolean company = p.company.get(date.toString());
        return !p.confirmed.containsKey(date.toString()) && company != null && official != null && !company
                .equals(official);
    }

    /** Include retained future segments; unrelated dates and already confirmed conflicts are omitted. */
    public static Set<String> disputedDates(Models.Plan plan, LocalDate from) {
        Set<String> dates = new TreeSet<>();
        collectDisputes(plan, from, dates);
        for (Models.Plan earlier : plan.previous) {
            collectDisputes(earlier, from, dates);
        }
        return dates;
    }

    private static void collectDisputes(Models.Plan segment, LocalDate from, Set<String> dates) {
        for (String key : segment.company.keySet()) {
            LocalDate date = LocalDate.parse(key);
            if (!date.isBefore(from) && PlanEdits.covers(segment, date) && disputed(segment, date)) {
                dates.add(key);
            }
        }
    }

    public static void apply(Models.Plan plan, Map<String, Boolean> days, boolean company) {
        (company ? plan.company : plan.official).putAll(days);
        for (Models.Plan earlier : plan.previous) {
            apply(earlier, days, company);
        }
        plan.validate();
    }

    /**
     * Local text interchange, at most 4000 explicit dates. No network or guessed values.
     */
    public static Map<String, Boolean> preview(String text) {
        require(text != null && text.length() <= 160000, "排班文件过大");
        Map<String, Boolean> values = new TreeMap<>();
        for (String line : text.replace("\uFEFF", "").split("\\r?\\n")) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("=", -1);
            require(parts.length == 2 && (parts[1].equals("WORK") || parts[1].equals("REST")),
                    "格式：YYYY-MM-DD=WORK 或 REST");
            String date = LocalDate.parse(parts[0]).toString();
            require(!values.containsKey(date), "排班中存在重复日期");
            values.put(date, parts[1].equals("WORK"));
        }
        require(!values.isEmpty() && values.size() <= 4000, "排班日期数量无效");
        return values;
    }
}
