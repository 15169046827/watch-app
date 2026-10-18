package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Consumer;

/** Todo deadlines use the same single-panel picker as calendar events. */
final class TodoEditor {
    private final Activity activity;
    private final LinearLayout parent;
    private final Plan draft;
    private final Consumer<Plan> preview;
    private Button deadline;

    TodoEditor(Activity activity, LinearLayout parent, Plan draft, Consumer<Plan> preview) {
        this.activity = activity;
        this.parent = parent;
        this.draft = draft;
        this.preview = preview;
    }

    void show() {
        EditText title = EditorControls.field(parent, "待办名称", draft.title);
        EditText category = EditorControls.field(parent, "分类", draft.category);
        deadline = EditorControls.row(parent, "截止", label(), this::choose);
        ScheduleUi.text(parent, "不设截止时不自动提醒；按天截止保留到当天结束，使用默认提醒时间（未设置为上午9点）。", 13);
        Button reminder = EditorControls.row(parent, "提醒", reminderLabel(), this::reminders);
        reminder.setTag("todo-reminder");
        ScheduleUi.primary(ScheduleUi.button(parent, "预览并保存", () -> EditorControls.safe(activity, () -> {
            Plan ready = Wire.copy(draft, Plan.class);
            ready.title = title.getText().toString().trim();
            ready.category = category.getText().toString().trim();
            ready.validate();
            preview.accept(ready);
        })));
    }

    private void reminders() {
        ReminderDialog.show(activity, draft, () -> {
            Button button = parent.findViewWithTag("todo-reminder");
            button.setText(activity.getString(R.string.schedule_option_summary,
                    activity.getString(R.string.schedule_reminder_label), reminderLabel()));
        });
    }

    private String reminderLabel() {
        if (draft.inheritReminders) {
            return "使用默认设置";
        }
        if (draft.reminderTargets == 2) {
            return "旧设置待处理（当前不提醒）";
        }
        if (draft.reminderTargets == 0) {
            return "不提醒";
        }
        return draft.reminderMinutes == 0 ? "截止时（按天使用默认时刻）" : "提前" + draft.reminderMinutes + "分钟";
    }

    private String label() {
        if (draft.todoDue.isEmpty()) {
            return "不设截止";
        }
        return draft.todoDue.replace('T', ' ') + (draft.dateOnly ? " · 当天结束" : "");
    }

    private void choose() {
        new AlertDialog.Builder(activity).setTitle("截止方式")
                .setItems(new String[] { "不设截止", "截止到某天", "精确到分钟" }, (dialog, which) -> {
                    if (which == 0) {
                        draft.todoDue = "";
                        draft.dateOnly = false;
                        deadline.setText(activity.getString(R.string.schedule_option_summary,
                                activity.getString(R.string.schedule_deadline_label), label()));
                    } else {
                        CalendarDateDialog.show(activity, "截止", initial(), which == 1, value -> {
                            draft.dateOnly = which == 1;
                            draft.todoDue = draft.dateOnly ? value.toLocalDate().toString() : value.toString();
                            deadline.setText(activity.getString(R.string.schedule_option_summary,
                                activity.getString(R.string.schedule_deadline_label), label()));
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private LocalDateTime initial() {
        if (draft.todoDue.isEmpty()) {
            return LocalDate.now(ZoneId.of(draft.zone)).atTime(9, 0);
        }
        return draft.dateOnly ? LocalDate.parse(draft.todoDue).atTime(9, 0) : LocalDateTime.parse(draft.todoDue);
    }
}
