package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import com.canace.x3schedule.domain.CalendarEditing;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Only confirmed sub-dialog choices enter the in-memory draft; saving remains an explicit preview action. */
final class PlanEditor {
    private final Activity activity;
    private final LinearLayout parent;
    private final Plan draft;
    private final boolean single;
    private final Consumer<Plan> preview;
    private EditText title;
    private EditText category;
    private LinearLayout options;

    PlanEditor(Activity activity, LinearLayout parent, Plan draft, boolean single, Consumer<Plan> preview) {
        this.activity = activity;
        this.parent = parent;
        this.draft = draft;
        this.single = single;
        this.preview = preview;
    }

    void show() {
        title = EditorControls.field(parent, "日程名称", draft.title);
        title.setHint("例如：下午开会");
        options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        parent.addView(options);
        refresh();
        category = EditorControls.field(parent, "分类", draft.category);
        ScheduleUi.text(parent, "日程时区 · " + draft.zone, 12);
        Button save = ScheduleUi.button(parent, "预览并保存", () -> EditorControls.safe(activity, () -> {
            Plan ready = Wire.copy(draft, Plan.class);
            ready.title = title.getText().toString().trim();
            ready.category = category.getText().toString().trim();
            ready.validate();
            preview.accept(ready);
        }));
        ScheduleUi.primary(save);
    }

    private void refresh() {
        options.removeAllViews();
        if (!single) {
            EditorControls.row(options, "用途", draft.kind == Kind.REMINDER ? "仅提醒，无需打卡" : "执行，记录完成",
                    this::kind);
        }
        if (draft.kind == Kind.REMINDER && !single) {
            Switch allDay = new Switch(activity);
            allDay.setText("全天日程");
            allDay.setContentDescription("全天日程");
            allDay.setMinHeight(ScheduleUi.dp(activity, 48));
            allDay.setChecked(draft.dateOnly);
            options.addView(allDay);
            allDay.setOnCheckedChangeListener((button, checked) -> {
                draft.dateOnly = checked;
                draft.daySpan = 1;
                resetExceptionSpans();
                draft.durationMinutes = checked ? 0 : 60;
                refresh();
            });
        }
        timeRows();
        if (!single) {
            EditorControls.row(options, "重复", CalendarEditing.repeat(draft),
                    () -> RepeatDialog.show(activity, draft, this::repeat));
            if (!CalendarEditing.once(draft)) {
                EditorControls.row(options, "结束重复", CalendarEditing.until(draft),
                        () -> RepeatDialog.end(activity, draft, this::repeat));
            }
            EditorControls.row(options, "提醒", reminderLabel(), this::reminders);
            if (draft.kind == Kind.EXECUTION) {
                EditorControls.row(options, "执行设置", "步骤与请假额度",
                        () -> ExecutionOptionsDialog.show(activity, draft));
            }
        }
    }

    private void timeRows() {
        EditorControls.row(options, draft.dateOnly ? "开始日期" : "开始时间", formatted(CalendarEditing.start(draft)),
                () -> CalendarDateDialog.show(activity, "开始", CalendarEditing.start(draft), draft.dateOnly,
                        value -> EditorControls.safe(activity, () -> changeStart(value))));
        if (!draft.dateOnly) {
            Switch hasEnd = new Switch(activity);
            hasEnd.setText("设置结束时间");
            hasEnd.setMinHeight(ScheduleUi.dp(activity, 48));
            hasEnd.setChecked(draft.durationMinutes > 0);
            options.addView(hasEnd);
            hasEnd.setOnCheckedChangeListener((button, checked) -> {
                draft.durationMinutes = checked ? 60 : 0;
                refresh();
            });
        }
        if (draft.dateOnly || draft.durationMinutes > 0) {
            EditorControls.row(options, draft.dateOnly ? "结束日期（含当天）" : "结束时间",
                    formatted(CalendarEditing.end(draft)), () -> CalendarDateDialog.show(activity, "结束",
                            CalendarEditing.end(draft), draft.dateOnly, value -> EditorControls.safe(activity, () -> {
                                CalendarEditing.times(draft, CalendarEditing.start(draft), value, true);
                                refresh();
                            })));
        }
    }

    private void changeStart(LocalDateTime value) {
        LocalDateTime end = draft.dateOnly ? value.plusDays(draft.daySpan - 1L)
                : value.atZone(ZoneId.of(draft.zone)).plusMinutes(draft.durationMinutes).toLocalDateTime();
        CalendarEditing.times(draft, value, end, draft.durationMinutes > 0);
        refresh();
    }

    private String formatted(LocalDateTime value) {
        return value.format(DateTimeFormatter.ofPattern(draft.dateOnly ? "yyyy年M月d日 EEE" : "yyyy年M月d日 EEE HH:mm"));
    }

    private void kind() {
        new AlertDialog.Builder(activity).setTitle("用途").setItems(new String[] { "仅提醒，无需打卡", "执行，记录完成" },
                (dialog, which) -> {
                    draft.kind = which == 0 ? Kind.REMINDER : Kind.EXECUTION;
                    if (draft.kind == Kind.EXECUTION) {
                        draft.dateOnly = false;
                        draft.daySpan = 1;
                        resetExceptionSpans();
                    }
                    refresh();
                }).setNegativeButton("取消", null).show();
    }

    private void repeat(Plan value) {
        draft.rule = value.rule;
        draft.interval = value.interval;
        draft.weekdays = value.weekdays;
        draft.nth = value.nth;
        draft.weekday = value.weekday;
        draft.rotation = value.rotation;
        draft.anchorSingleRest = value.anchorSingleRest;
        draft.count = value.count;
        draft.until = value.until;
        refresh();
    }

    private void resetExceptionSpans() {
        for (com.canace.x3schedule.domain.Models.ExceptionDay exception : draft.exceptions.values()) {
            exception.daySpan = null;
        }
    }

    private String reminderLabel() {
        if (draft.inheritReminders) {
            return "使用默认设置";
        }
        if (draft.reminderTargets == 0) {
            return "不提醒";
        }
        return draft.dateOnly ? "按默认时刻提醒" : draft.reminderMinutes == 0 ? "开始时" : "提前" + draft.reminderMinutes + "分钟";
    }

    private void reminders() {
        ReminderDialog.show(activity, draft, this::refresh);
    }
}
