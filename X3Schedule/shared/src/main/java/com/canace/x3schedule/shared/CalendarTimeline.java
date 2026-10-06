package com.canace.x3schedule.shared;

import android.app.Activity;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.canace.x3schedule.domain.CalendarDay;
import com.canace.x3schedule.domain.Models.Instance;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native accessible event buttons on a scrollable wall-clock grid. */
final class CalendarTimeline {
    private final Activity activity;
    private final LinearLayout parent;
    private final Map<LocalDate, CalendarDay> days;
    private final int width;
    private final int offset;
    private final ZoneId zone;
    private final CalendarScreen.Actions actions;

    CalendarTimeline(Activity activity, LinearLayout parent, Map<LocalDate, CalendarDay> days,
            ZoneId zone, CalendarScreen.Actions actions, int width, int offset) {
        this.activity = activity;
        this.parent = parent;
        this.days = days;
        this.zone = zone;
        this.actions = actions;
        this.width = width;
        this.offset = offset;
    }

    void show() {
        ScheduleUi.text(parent, days.size() == 7 ? "左右滑动查看整周，点击空白时段新建。" : "点击空白时段新建日程。", 13);
        HorizontalScrollView horizontal = new HorizontalScrollView(activity);
        LinearLayout columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        axis(columns);
        for (LocalDate day : days.keySet()) {
            column(columns, day, width);
        }
        horizontal.addView(columns);
        parent.addView(horizontal);
        horizontal.post(() -> {
            if (horizontal.isAttachedToWindow()) {
                horizontal.scrollTo(dp(offset), 0);
                horizontal.setOnScrollChangeListener((view, x, y, oldX, oldY) -> actions.scroll(
                        Math.round(x / activity.getResources().getDisplayMetrics().density)));
            }
        });
    }

    private void axis(LinearLayout columns) {
        LinearLayout axis = new LinearLayout(activity);
        axis.setOrientation(LinearLayout.VERTICAL);
        columns.addView(axis, new LinearLayout.LayoutParams(dp(48), -2));
        TextView top = ScheduleUi.text(axis, "", 12);
        top.setHeight(dp(96));
        for (int hour = 0; hour < 24; hour++) {
            TextView label = ScheduleUi.text(axis, String.format(Locale.ROOT, "%02d:00", hour), 12);
            label.setHeight(dp(64));
            label.setGravity(Gravity.TOP | Gravity.END);
        }
    }

    private void column(LinearLayout columns, LocalDate day, int width) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        columns.addView(column, new LinearLayout.LayoutParams(dp(width), -2));
        Button date = ScheduleUi.button(column, day.format(DateTimeFormatter.ofPattern("M/d EEE")),
                () -> actions.select(day, 2));
        date.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        date.setPadding(0, 0, 0, 0);
        if (day.equals(LocalDate.now(zone))) {
            ScheduleUi.primary(date);
        }
        allDay(column, day);
        FrameLayout grid = new FrameLayout(activity);
        column.addView(grid, new LinearLayout.LayoutParams(-1, dp(24 * 64 + 48)));
        for (int hour = 0; hour < 24; hour++) {
            int minute = hour * 60;
            Button blank = new Button(activity);
            // Native button elevation otherwise covers the later, flat event cards and steals their taps.
            blank.setStateListAnimator(null);
            blank.setElevation(0);
            blank.setText("");
            blank.setContentDescription(day + " " + String.format(Locale.ROOT, "%02d:00，新建日程", hour));
            blank.setBackgroundColor(activity.getColor(hour % 2 == 0
                    ? R.color.schedule_card : R.color.schedule_background));
            blank.setOnClickListener(view -> actions.create(day, minute));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(64));
            params.topMargin = dp(hour * 64);
            grid.addView(blank, params);
        }
        for (CalendarDay.Block slot : days.get(day).blocks) {
            event(grid, slot, width);
        }
    }

    private void allDay(LinearLayout column, LocalDate day) {
        List<Instance> allDay = days.get(day).allDay;
        Button button = ScheduleUi.button(column, allDay.isEmpty() ? "全天 · 无" : "全天 / 截止 · " + allDay.size(),
                () -> showAllDay(day, allDay));
        button.setTextSize(12);
        button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        button.setEnabled(!allDay.isEmpty());
    }

    private void showAllDay(LocalDate day, List<Instance> allDay) {
        CalendarItemDialog.show(activity, day + " 全天 / 按天截止", allDay, zone, actions::detail);
    }

    private void event(FrameLayout grid, CalendarDay.Block slot, int width) {
        Button button = new Button(activity);
        ScheduleUi.button(button);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setTextSize(12);
        button.setGravity(Gravity.TOP | Gravity.START);
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        Instance first = slot.items.get(0);
        button.setText(slot.items.size() == 1 ? first.title + "\n" + CalendarScreen.label(first, zone)
                : slot.items.size() + "项日程\n点击查看全部");
        button.setContentDescription(button.getText());
        button.setBackground(ScheduleUi.surface(activity, R.color.schedule_subtle));
        button.setOnClickListener(view -> {
            if (slot.items.size() == 1) {
                actions.detail(first);
            } else {
                CalendarItemDialog.show(activity, "此时段的日程", slot.items, zone, actions::detail);
            }
        });
        int left = dp(width) * slot.column / slot.columns;
        int right = dp(width) * (slot.column + 1) / slot.columns;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.max(1, right - left - dp(2)),
                dp((slot.end - slot.start) * 64 / 60));
        params.topMargin = dp(slot.start * 64 / 60);
        params.leftMargin = left;
        grid.addView(button, params);
    }

    private int dp(int value) {
        return ScheduleUi.dp(activity, value);
    }
}
