package com.canace.x3schedule.shared;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import java.time.LocalTime;

/** Start is a clock picker; users explicitly choose a point, an end clock, or a duration. */
final class TimePlanFields {
    private final EditText start;
    private final EditText end;
    private final EditText duration;
    private final Spinner mode;
    private final Spinner precision;
    private final LinearLayout precisionGroup;
    private final LinearLayout clockGroup;
    private boolean reminderKind;

    TimePlanFields(Activity activity, LinearLayout parent, Plan source) {
        precisionGroup = group(parent);
        ScheduleUi.text(precisionGroup, "提醒时间精度", 14);
        precision = new Spinner(activity);
        precision.setContentDescription("提醒时间精度");
        ScheduleUi.choice(precision);
        precision.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "具体时间", "全天" }));
        precisionGroup.addView(precision);
        ScheduleUi.text(precisionGroup, "全天提醒使用设置中的默认提醒时间，未设置时为上午 9 点。", 14);
        precision.setSelection(source.dateOnly ? 1 : 0);
        clockGroup = group(parent);
        parent = clockGroup;
        start = field(parent, "开始时间", LocalTime.MIN.plusMinutes(source.startMinute).toString());
        DateTimeFields.time(start);
        ScheduleUi.text(parent, "结束方式", 14);
        mode = new Spinner(activity);
        mode.setContentDescription("结束方式");
        ScheduleUi.choice(mode);
        mode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "不设结束时间", "选择结束时间", "设置时长" }));
        parent.addView(mode);
        LinearLayout endGroup = group(parent);
        end = field(endGroup, "结束时间（早于或等于开始时按次日）", LocalTime.MIN
                .plusMinutes(source.startMinute + source.durationMinutes).toString());
        DateTimeFields.time(end);
        LinearLayout durationGroup = group(parent);
        duration = field(durationGroup, "计划时长（分钟）", Integer.toString(source.durationMinutes));
        duration.setInputType(InputType.TYPE_CLASS_NUMBER);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> owner, View view, int position, long id) {
                endGroup.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                durationGroup.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> owner) {
                // This spinner always has a selection.
            }
        });
        mode.setSelection(source.durationMinutes == 0 ? 0 : 2);
        reminderKind = source.kind == Kind.REMINDER;
        PickerChoices.observe(precision, this::refresh);
    }

    void setKind(Kind kind) {
        reminderKind = kind == Kind.REMINDER;
        refresh();
    }

    private void refresh() {
        precisionGroup.setVisibility(reminderKind ? View.VISIBLE : View.GONE);
        clockGroup.setVisibility(reminderKind && precision.getSelectedItemPosition() == 1
                ? View.GONE : View.VISIBLE);
    }

    void apply(Plan plan) {
        plan.dateOnly = plan.kind == Kind.REMINDER && precision.getSelectedItemPosition() == 1;
        if (plan.dateOnly) {
            plan.startMinute = 0;
            plan.durationMinutes = 0;
            return;
        }
        plan.startMinute = TimeRange.minute(start.getText().toString());
        int selected = mode.getSelectedItemPosition();
        plan.durationMinutes = selected == 0 ? 0 : selected == 1
                ? TimeRange.duration(start.getText().toString(), end.getText().toString())
                : Integer.parseInt(duration.getText().toString().trim());
    }

    private static LinearLayout group(LinearLayout parent) {
        LinearLayout group = new LinearLayout(parent.getContext());
        group.setOrientation(LinearLayout.VERTICAL);
        parent.addView(group);
        return group;
    }

    private static EditText field(LinearLayout parent, String label, String initial) {
        ScheduleUi.text(parent, label, 14);
        EditText field = new EditText(parent.getContext());
        ScheduleUi.field(field);
        field.setContentDescription(label);
        field.setSingleLine(true);
        field.setText(initial);
        parent.addView(field);
        return field;
    }
}
