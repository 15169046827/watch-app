package com.canace.x3schedule.shared;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3schedule.domain.CalendarDay;
import com.canace.x3schedule.domain.CalendarWindow;
import com.canace.x3schedule.domain.DayPrecision;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.ProgressReport;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Phone calendar. Its projection reads a consistent snapshot and never writes business data. */
final class CalendarScreen {
    interface Actions {
        void select(LocalDate day, int mode);
        void create(LocalDate day, int minute);
        void detail(Instance item);
        void scroll(int offset);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-calendar"));
    private static final AtomicLong REQUESTS = new AtomicLong();
    private final Activity activity;
    private final LinearLayout parent;
    private final CalendarSurface surface;
    private final LocalDate selected;
    private final int mode;
    private final int offset;
    private final int width;
    private final Actions actions;
    private final Runnable loaded;
    private final ZoneId zone = ZoneId.systemDefault();
    private Map<String, Progress.Summary> progress = new java.util.HashMap<>();
    private final Map<LocalDate, ProgressReport.Summary> scores = new LinkedHashMap<>();

    CalendarScreen(Activity activity, LinearLayout parent, LocalDate selected, int mode, int offset,
            Actions actions, Runnable loaded, CalendarSurface surface) {
        this.activity = activity;
        this.surface = surface;
        surface.attach(parent);
        this.parent = surface.body;
        this.selected = selected;
        this.mode = mode;
        this.offset = offset;
        int viewport = Math.round(activity.getResources().getDisplayMetrics().widthPixels
                / activity.getResources().getDisplayMetrics().density);
        width = mode == 1 ? 140 : Math.max(220, Math.min(640, viewport) - 94);
        this.actions = actions;
        this.loaded = loaded;
    }

    void show() {
        surface.begin();
        if (parent.getChildCount() == 0) {
            toolbar();
        }
        LocalDate first = firstDay();
        LocalDate last = first.plusDays(mode == 0 ? 41 : mode == 1 ? 6 : 0);
        Context context = activity.getApplicationContext();
        long request = REQUESTS.incrementAndGet();
        WORKER.execute(() -> {
            if (request != REQUESTS.get()) {
                return;
            }
            try {
                Map<LocalDate, CalendarDay> days = prepare(read(context, first, last), first, last);
                parent.post(() -> {
                    if (active() && request == REQUESTS.get()) {
                        parent.removeAllViews();
                        toolbar();
                        render(days, first);
                        surface.finish();
                        parent.post(loaded);
                    }
                });
            } catch (RuntimeException error) {
                parent.post(() -> {
                    if (active() && request == REQUESTS.get()) {
                        surface.finish();
                        Toast.makeText(activity, "日历未能读取，请重新选择日期重试。已保存日程保留。",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private boolean active() {
        return !activity.isDestroyed() && !activity.isFinishing() && parent.isAttachedToWindow();
    }

    private List<Instance> read(Context context, LocalDate first, LocalDate last) {
        try (Store store = new Store(context)) {
            store.getReadableDatabase().beginTransaction();
            try {
                List<Instance> items = CalendarWindow.read(store.plans(), store.instances(), store.events(),
                        store.settings(), first, last, zone, System.currentTimeMillis());
                List<com.canace.x3schedule.domain.Models.Event> events = store.events();
                progress = Progress.summaries(items, events);
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    scores.put(day, ProgressReport.range(items, events,
                            day, day, System.currentTimeMillis()));
                }
                store.getReadableDatabase().setTransactionSuccessful();
                return items;
            } finally {
                store.getReadableDatabase().endTransaction();
            }
        }
    }

    private LocalDate firstDay() {
        LocalDate day = mode == 0 ? selected.withDayOfMonth(1) : selected;
        return mode == 2 ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private void toolbar() {
        LinearLayout modes = row(parent);
        String[] labels = { "月", "周", "日" };
        for (int n = 0; n < labels.length; n++) {
            final int target = n;
            Button button = cell(modes, labels[n], () -> actions.select(selected, target));
            button.setContentDescription(labels[n] + "视图" + (mode == n ? "，已选中" : ""));
            if (mode == n) {
                ScheduleUi.primary(button);
            }
        }
        LinearLayout navigation = row(parent);
        cell(navigation, "‹", () -> move(-1)).setContentDescription("上一" + labels[mode]);
        Button date = cell(navigation, selected.format(DateTimeFormatter.ofPattern("yyyy年M月")), this::jump);
        date.setContentDescription("选择日期，当前" + selected);
        cell(navigation, "›", () -> move(1)).setContentDescription("下一" + labels[mode]);
        LinearLayout tools = row(parent);
        cell(tools, "今天", () -> actions.select(LocalDate.now(zone), mode));
        ScheduleUi.primary(cell(tools, "＋ 新建", () -> actions.create(selected, 9 * 60)));
    }

    private void jump() {
        new android.app.DatePickerDialog(activity, (picker, year, month, day) ->
                actions.select(LocalDate.of(year, month + 1, day), mode), selected.getYear(),
                selected.getMonthValue() - 1, selected.getDayOfMonth()).show();
    }

    private void move(int direction) {
        LocalDate day = mode == 0 ? selected.plusMonths(direction)
                : selected.plusDays(mode == 1 ? 7L * direction : direction);
        actions.select(day, mode);
    }

    private Map<LocalDate, CalendarDay> prepare(List<Instance> items, LocalDate first, LocalDate last) {
        Map<LocalDate, CalendarDay> days = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            days.put(day, new CalendarDay(items, day, zone, mode == 0 ? 0 : width));
        }
        return days;
    }

    private void render(Map<LocalDate, CalendarDay> days, LocalDate first) {
        if (mode == 0) {
            month(days, first);
            dayList(days.get(selected).items);
        } else {
            new CalendarTimeline(activity, parent, days, zone, actions, width, offset).progress(progress).show();
        }
        ScheduleUi.text(parent, "时间按本机时区显示 · " + zone.getId(), 12);
    }

    private void month(Map<LocalDate, CalendarDay> days, LocalDate first) {
        LinearLayout weekdays = row(parent);
        for (String label : new String[] { "一", "二", "三", "四", "五", "六", "日" }) {
            TextView text = ScheduleUi.text(weekdays, label, 13);
            text.setGravity(Gravity.CENTER);
            text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        }
        for (int week = 0; week < 6; week++) {
            LinearLayout row = row(parent);
            row.setBaselineAligned(false);
            for (int dow = 0; dow < 7; dow++) {
                LocalDate day = first.plusDays(week * 7L + dow);
                int count = days.get(day).items.size();
                // Every cell reserves the marker line, so event density never changes its baseline or height.
                String label = day.getDayOfMonth() + "\n" + (count == 0 ? "\u00a0" : "·");
                Button button = cell(row, label, () -> actions.select(day, mode));
                button.setTextSize(15);
                button.setPadding(0, 0, 0, 0);
                LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) button.getLayoutParams();
                size.height = LinearLayout.LayoutParams.MATCH_PARENT;
                button.setLayoutParams(size);
                button.setContentDescription(day + (day.equals(LocalDate.now(zone)) ? "，今天" : "")
                        + "，" + count + "项日程" + (day.equals(selected) ? "，已选中" : ""));
                if (!YearMonth.from(day).equals(YearMonth.from(selected))) {
                    button.setTextColor(activity.getColor(R.color.schedule_muted));
                }
                if (day.equals(selected)) {
                    ScheduleUi.primary(button);
                }
                monthScore(button, day);
            }
        }
    }

    private void monthScore(Button button, LocalDate day) {
        ProgressReport.Summary score = scores.get(day);
        if (score == null || score.results == 0 && score.pending == 0) {
            return;
        }
        String marker = score.score() == null
                ? "○" : (int) Math.floor(score.score()) + (score.pending > 0 ? "○" : "·");
        button.setText(activity.getString(R.string.schedule_day_score, day.getDayOfMonth(), marker));
        button.setContentDescription(button.getContentDescription() + "，计划执行得分"
                + ProgressReport.display(score.score()) + "，待确认" + score.pending);
        if (!day.equals(selected)) {
            ProgressAppearance.score(button, score.score());
        }
    }

    private void dayList(List<Instance> items) {
        ScheduleUi.heading(parent, selected.format(DateTimeFormatter.ofPattern("M月d日 EEEE")));
        List<Instance> today = items;
        if (today.isEmpty()) {
            ScheduleUi.text(parent, "这一天没有日程，点击“＋ 新建”安排。", 15);
        }
        if (today.size() > 50) {
            ScheduleUi.button(parent, "查看当天全部 " + today.size() + " 项日程",
                    () -> CalendarItemDialog.show(activity, selected.toString(), today, zone,
                            progress, actions::detail));
            ScheduleUi.text(parent, "下方显示前50项；完整列表可查看所有日程。", 13);
        }
        for (Instance item : today.subList(0, Math.min(50, today.size()))) {
            Button button = ScheduleUi.button(parent, item.title + "\n" + label(item, zone),
                    () -> actions.detail(item));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            ProgressAppearance.item(button, item, progress);
        }
    }

    static String label(Instance item, ZoneId zone) {
        if (item.progress != null && item.progress.untimed) {
            return item.taskDay + " · 不定时任务";
        }
        if (!item.timed) {
            return "不设截止";
        }
        if (item.dateOnly) {
            String range = DayPrecision.day(item).toString();
            if (item.daySpan > 1) {
                range += " – " + DayPrecision.day(item).plusDays(item.daySpan - 1L);
            }
            return range + (item.kind == Kind.TODO ? " · 当天结束时截止" : " · 全天");
        }
        DateTimeFormatter format = DateTimeFormatter.ofPattern("M月d日 HH:mm");
        String start = Instant.ofEpochMilli(item.start).atZone(zone).format(format);
        if (item.kind == Kind.TODO) {
            return start + " 截止";
        }
        return item.start == item.end ? start : start + " – "
                + Instant.ofEpochMilli(item.end).atZone(zone).format(format);
    }

    static LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row);
        return row;
    }

    static Button cell(LinearLayout row, String label, Runnable action) {
        Button button = ScheduleUi.button(row, label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(1, 1, 1, 1);
        button.setLayoutParams(params);
        return button;
    }
}
