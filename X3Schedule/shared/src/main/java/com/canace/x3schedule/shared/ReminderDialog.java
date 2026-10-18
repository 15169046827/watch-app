package com.canace.x3schedule.shared;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
        ScheduleUi.text(panel, "手机负责提醒；手表负责查看、记录和同步。关闭手机提醒后，手表不会兜底。", 14);
        ScheduleUi.text(panel, "开启提醒会为开始、结束或截止登记系统闹钟，可能显示“下一个闹钟”。", 13);
        boolean legacy = draft.reminderTargets == 2;
        Spinner target = new Spinner(activity);
        ScheduleUi.choice(target);
        target.setContentDescription("提醒端");
        target.setAdapter(new android.widget.ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, legacy
                ? new String[] { "不提醒", "手机提醒", "旧设置待处理（当前不提醒）" }
                : new String[] { "不提醒", "手机提醒" }));
        target.setSelection(legacy ? 2 : draft.reminderTargets & 1);
        target.setEnabled(!inherit.isChecked());
        inherit.setOnCheckedChangeListener((button, checked) -> target.setEnabled(!checked));
        panel.addView(target);
        ScheduleUi.text(panel, "要单独调整此日程，请先取消“使用默认提醒设置”。", 13);
        EditorControls.confirm(activity, "提醒设置", panel, () -> {
            int value = draft.dateOnly ? 0 : Integer.parseInt(minutes.getText().toString().trim());
            com.canace.x3schedule.domain.Models.require(value >= 0 && value <= 1440, "提前分钟应为0至1440");
            draft.reminderMinutes = value;
            draft.inheritReminders = inherit.isChecked();
            draft.reminderTargets = target.getSelectedItemPosition();
            changed.run();
        });
    }

}
