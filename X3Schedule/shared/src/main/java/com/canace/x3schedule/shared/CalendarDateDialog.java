package com.canace.x3schedule.shared;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** One confirmation commits the date and clock together. Dismissal discards dialog-local changes. */
final class CalendarDateDialog {
    private CalendarDateDialog() {
    }

    static void show(Context context, String title, LocalDateTime initial, boolean dateOnly,
            Consumer<LocalDateTime> accept) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = ScheduleUi.dp(context, 16);
        panel.setPadding(padding, 0, padding, padding);
        TextView summary = ScheduleUi.text(panel, "", 16);
        LinearLayout dates = CalendarScreen.row(panel);
        NumberPicker year = picker(dates, "年", Math.min(1971, initial.getYear()),
                Math.max(2200, initial.getYear()), initial.getYear());
        NumberPicker month = picker(dates, "月", 1, 12, initial.getMonthValue());
        NumberPicker day = picker(dates, "日", 1, initial.toLocalDate().lengthOfMonth(), initial.getDayOfMonth());
        LinearLayout clocks = CalendarScreen.row(panel);
        NumberPicker hour = picker(clocks, "时", 0, 23, initial.getHour());
        NumberPicker minute = picker(clocks, "分", 0, 59, initial.getMinute());
        clocks.setVisibility(dateOnly ? android.view.View.GONE : android.view.View.VISIBLE);
        Runnable refresh = () -> {
            day.setMaxValue(YearMonth.of(year.getValue(), month.getValue()).lengthOfMonth());
            LocalDateTime value = value(year, month, day, hour, minute);
            summary.setText(value.format(DateTimeFormatter.ofPattern(dateOnly
                    ? "yyyy年M月d日 EEEE" : "yyyy年M月d日 EEEE HH:mm")));
        };
        for (NumberPicker picker : new NumberPicker[] { year, month, day, hour, minute }) {
            picker.setOnValueChangedListener((view, old, value) -> refresh.run());
        }
        refresh.run();
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("确定", (target, which) ->
                        accept.accept(value(year, month, day, hour, minute))).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(-1, -2);
        }
    }

    private static LocalDateTime value(NumberPicker year, NumberPicker month, NumberPicker day,
            NumberPicker hour, NumberPicker minute) {
        return LocalDate.of(year.getValue(), month.getValue(), day.getValue()).atTime(hour.getValue(),
                minute.getValue());
    }

    static NumberPicker picker(LinearLayout parent, String label, int min, int max, int initial) {
        LinearLayout column = new LinearLayout(parent.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = ScheduleUi.text(column, label, 13);
        name.setGravity(Gravity.CENTER);
        NumberPicker picker = new NumberPicker(parent.getContext());
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(initial);
        picker.setWrapSelectorWheel(false);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setContentDescription(label);
        column.addView(picker, new LinearLayout.LayoutParams(-1, ScheduleUi.dp(parent.getContext(), 112)));
        return picker;
    }
}
