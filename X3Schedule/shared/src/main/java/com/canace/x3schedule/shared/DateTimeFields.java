package com.canace.x3schedule.shared;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Native pickers; a cancelled dialog never replaces the current value. */
final class DateTimeFields {
    private DateTimeFields() {
    }

    static void date(EditText field, boolean optional) {
        prepare(field);
        if (optional) {
            field.setHint("未设置（不限）");
        }
        field.setOnClickListener(view -> {
            LocalDate initial = dateValue(field.getText().toString(), LocalDate.now());
            DatePickerDialog dialog = new DatePickerDialog(field.getContext(), (picker, year, month, day) ->
                    field.setText(LocalDate.of(year, month + 1, day).toString()),
                    initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
            if (optional) {
                dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "不设置", (target, which) -> field.setText(""));
            }
            dialog.show();
        });
    }

    static void time(EditText field) {
        prepare(field);
        field.setOnClickListener(view -> {
            LocalTime initial;
            try {
                initial = LocalTime.parse(field.getText().toString());
            } catch (RuntimeException error) {
                initial = LocalTime.of(9, 0);
            }
            pickTime(field, initial, value -> field.setText(value.toString()));
        });
    }

    static void dateTime(EditText field, String zone, boolean optional) {
        prepare(field);
        field.setText(field.getText().toString().replace('T', ' '));
        if (optional) {
            field.setHint("无截止时间");
        }
        field.setOnClickListener(view -> {
            LocalDateTime initial = dateTimeValue(field, zone);
            DatePickerDialog dialog = new DatePickerDialog(field.getContext(), (picker, year, month, day) -> {
                LocalDate date = LocalDate.of(year, month + 1, day);
                pickTime(field, initial.toLocalTime(), time -> field.setText(date.atTime(time)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
            }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
            if (optional) {
                dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "无截止", (target, which) -> field.setText(""));
            }
            dialog.show();
        });
    }

    static String isoDateTime(EditText field) {
        return field.getText().toString().trim().replace(' ', 'T');
    }

    private static LocalDate dateValue(String value, LocalDate fallback) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static LocalDateTime dateTimeValue(EditText field, String zone) {
        try {
            return LocalDateTime.parse(isoDateTime(field));
        } catch (RuntimeException error) {
            return LocalDate.now(ZoneId.of(zone)).atTime(9, 0);
        }
    }

    private static void pickTime(EditText field, LocalTime initial, java.util.function.Consumer<LocalTime> save) {
        new TimePickerDialog(field.getContext(), (picker, hour, minute) -> save.accept(LocalTime.of(hour, minute)),
                initial.getHour(), initial.getMinute(), true).show();
    }

    private static void prepare(EditText field) {
        field.setKeyListener(null);
        field.setCursorVisible(false);
        field.setFocusable(false);
        field.setClickable(true);
    }
}
