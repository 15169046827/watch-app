package com.canace.x3schedule.shared;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Wire;
import java.util.ArrayList;

/** Execution-only options remain secondary to the calendar editor. */
final class ExecutionOptionsDialog {
    private ExecutionOptionsDialog() {
    }

    static void show(Activity activity, Plan draft) {
        LinearLayout panel = EditorControls.panel(activity);
        EditText steps = EditorControls.field(panel, "执行步骤（每行一步，可留空）", String.join("\n", draft.steps));
        steps.setSingleLine(false);
        steps.setMinLines(3);
        EditText week = quota(panel, "每周请假次数（-1 使用默认设置）", draft.leaveWeek);
        EditText month = quota(panel, "每月请假次数（-1 使用默认设置）", draft.leaveMonth);
        EditorControls.confirm(activity, "执行设置", panel, () -> {
            Plan checked = Wire.copy(draft, Plan.class);
            checked.steps = new ArrayList<>();
            for (String line : steps.getText().toString().split("\n")) {
                if (!line.trim().isEmpty()) {
                    checked.steps.add(line.trim());
                }
            }
            checked.leaveWeek = Integer.parseInt(week.getText().toString().trim());
            checked.leaveMonth = Integer.parseInt(month.getText().toString().trim());
            com.canace.x3schedule.domain.Models.require(checked.steps.size() <= 30, "最多30个步骤");
            for (String step : checked.steps) {
                com.canace.x3schedule.domain.Models.require(step.length() <= 160, "每个步骤最多160字");
            }
            com.canace.x3schedule.domain.Models.require(checked.leaveWeek >= -1 && checked.leaveMonth >= -1,
                    "请假次数不能小于-1");
            draft.steps = checked.steps;
            draft.leaveWeek = checked.leaveWeek;
            draft.leaveMonth = checked.leaveMonth;
        });
    }

    private static EditText quota(LinearLayout parent, String label, int value) {
        EditText field = EditorControls.field(parent, label, Integer.toString(value));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }
}
