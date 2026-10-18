package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Switch;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.Wire;
import java.util.Locale;

/** All optional rules are edited in a copy; cancellation cannot change the calendar draft. */
final class ProgressOptionsDialog {
    private ProgressOptionsDialog() {
    }

    static void show(Activity activity, Plan plan, Runnable changed) {
        Progress.Options options = plan.progress == null ? new Progress.Options()
                : Wire.copy(plan.progress, Progress.Options.class);
        LinearLayout panel = EditorControls.panel(activity);
        ScheduleUi.text(panel, "三档标准全部留空时，仅提供完成/未完成。成果等级与计划执行得分分别展示。", 14);
        Switch untimed = toggle(panel, "不定时任务（提醒时间不构成履约承诺）", options.untimed);
        LinearLayout untimedPanel = EditorControls.panel(activity);
        panel.addView(untimedPanel);
        int[] reminder = optionalTime(untimedPanel, "任务提醒", options.remindMinute, 540);
        int[] checkin = optionalTime(untimedPanel, "成果反馈", options.checkinMinute, options.boundaryMinute);
        ScheduleUi.text(untimedPanel, "未自定时：提醒采用设置中的默认时间，成果反馈在任务日结束时询问。", 13);
        untimedPanel.setVisibility(options.untimed ? android.view.View.VISIBLE : android.view.View.GONE);
        Switch scored = toggle(panel, "计入计划执行得分", options.scored);
        Switch yield = toggle(panel, "允许被补救占用（每次仍需确认）", options.allowYield);
        EditText perfect = EditorControls.field(panel, "完整完成标准（可全部留空）", options.perfect);
        EditText basic = EditorControls.field(panel, "基本完成标准", options.basic);
        EditText minimum = EditorControls.field(panel, "最低行动标准", options.minimum);
        LinearLayout recoveryPanel = EditorControls.panel(activity);
        panel.addView(recoveryPanel);
        Switch recovery = toggle(recoveryPanel, "预设正式补救时段", options.recoveryMinute >= 0);
        int[] minute = { Math.max(0, options.recoveryMinute) };
        Button time = ScheduleUi.button(recoveryPanel, time(minute[0]), () -> { });
        time.setOnClickListener(view -> new TimePickerDialog(activity, (picker, hour, value) -> {
            minute[0] = hour * 60 + value;
            time.setText(time(minute[0]));
        }, minute[0] / 60, minute[0] % 60, true).show());
        Switch next = toggle(recoveryPanel, "补救在次日凌晨（仍须在任务日结束前）", options.recoveryDayOffset == 1);
        ScheduleUi.text(recoveryPanel, "补救时长（分钟）", 14);
        NumberPicker duration = new NumberPicker(activity);
        duration.setMinValue(1);
        duration.setMaxValue(1440);
        duration.setValue(options.recoveryDuration);
        duration.setContentDescription("补救时长，分钟");
        recoveryPanel.addView(duration);
        recoveryPanel.setVisibility(options.untimed ? android.view.View.GONE : android.view.View.VISIBLE);
        untimed.setOnCheckedChangeListener((button, checked) -> {
            untimedPanel.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE);
            recoveryPanel.setVisibility(checked ? android.view.View.GONE : android.view.View.VISIBLE);
        });
        EditorControls.confirm(activity, "完成标准与补救", panel, () -> {
            options.untimed = untimed.isChecked();
            options.remindMinute = reminder[0];
            options.checkinMinute = checkin[0];
            options.scored = scored.isChecked();
            options.allowYield = yield.isChecked();
            options.perfect = perfect.getText().toString().trim();
            options.basic = basic.getText().toString().trim();
            options.minimum = minimum.getText().toString().trim();
            options.recoveryMinute = recovery.isChecked() && !options.untimed ? minute[0] : -1;
            options.recoveryDayOffset = next.isChecked() ? 1 : 0;
            options.recoveryDuration = duration.getValue();
            options.validate();
            plan.progress = options;
            changed.run();
        });
    }

    private static int[] optionalTime(LinearLayout panel, String title, int configured, int fallback) {
        int[] value = { configured };
        Switch custom = toggle(panel, "自定" + title + "时间", configured >= 0);
        Button button = ScheduleUi.button(panel, clock(title, configured < 0 ? fallback : configured), () -> { });
        button.setEnabled(configured >= 0);
        custom.setOnCheckedChangeListener((toggle, checked) -> {
            button.setEnabled(checked);
            value[0] = checked ? fallback : -1;
            button.setText(clock(title, fallback));
        });
        button.setOnClickListener(view -> {
            int initial = value[0] < 0 ? fallback : value[0];
            new TimePickerDialog(panel.getContext(), (picker, hour, minute) -> {
                value[0] = hour * 60 + minute;
                button.setText(clock(title, value[0]));
            }, initial / 60, initial % 60, true).show();
        });
        return value;
    }

    private static String clock(String title, int minute) {
        return String.format(Locale.ROOT, "%s %02d:%02d", title, minute / 60, minute % 60);
    }

    private static String time(int minute) {
        return String.format(Locale.ROOT, "补救开始 %02d:%02d", minute / 60, minute % 60);
    }

    private static Switch toggle(LinearLayout panel, String label, boolean checked) {
        Switch toggle = new Switch(panel.getContext());
        toggle.setText(label);
        toggle.setContentDescription(label);
        toggle.setMinHeight(ScheduleUi.dp(panel.getContext(), 48));
        toggle.setChecked(checked);
        panel.addView(toggle);
        return toggle;
    }
}
