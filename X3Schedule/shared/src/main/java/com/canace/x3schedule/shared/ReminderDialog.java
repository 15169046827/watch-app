package com.canace.x3schedule.shared;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.canace.x3schedule.domain.Models.Plan;

/** Shared reminder options, committed only after explicit confirmation. */
final class ReminderDialog {
    private ReminderDialog() {
    }

    static void show(Activity activity, Plan draft, Runnable changed) {
        LinearLayout panel = EditorControls.panel(activity);
        android.widget.CheckBox inherit = new android.widget.CheckBox(activity);
        inherit.setText("使用默认提醒设置");
        inherit.setChecked(draft.inheritReminders);
        panel.addView(inherit);
        EditText minutes = EditorControls.field(panel, "提前分钟（仅具体时间）", Integer.toString(draft.reminderMinutes));
        minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minutes.setVisibility(draft.dateOnly ? View.GONE : View.VISIBLE);
        android.widget.CheckBox phone = endpoint(panel, "手机提醒", (draft.reminderTargets & 1) != 0);
        android.widget.CheckBox watch = endpoint(panel, "手表提醒", (draft.reminderTargets & 2) != 0);
        EditorControls.confirm(activity, "提醒设置", panel, () -> {
            int value = draft.dateOnly ? 0 : Integer.parseInt(minutes.getText().toString().trim());
            com.canace.x3schedule.domain.Models.require(value >= 0 && value <= 1440, "提前分钟应为0至1440");
            draft.reminderMinutes = value;
            draft.inheritReminders = inherit.isChecked();
            draft.reminderTargets = (phone.isChecked() ? 1 : 0) | (watch.isChecked() ? 2 : 0);
            changed.run();
        });
    }

    private static android.widget.CheckBox endpoint(LinearLayout panel, String label, boolean checked) {
        android.widget.CheckBox field = new android.widget.CheckBox(panel.getContext());
        field.setText(label);
        field.setChecked(checked);
        panel.addView(field);
        return field;
    }
}
