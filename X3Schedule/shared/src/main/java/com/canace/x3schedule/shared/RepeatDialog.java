package com.canace.x3schedule.shared;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import com.canace.x3schedule.domain.CalendarEditing;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.util.function.Consumer;

final class RepeatDialog {
    private static final Rule[] RULES = { Rule.DAYS, Rule.WEEKS, Rule.MONTH_DAY, Rule.YEARLY,
        Rule.MONTH_NTH, Rule.WORKDAYS, Rule.ALTERNATING, Rule.ROTATION };

    private RepeatDialog() {
    }

    static void show(Context context, Plan source, Consumer<Plan> accept) {
        String[] labels = { "不重复", "按天", "按周（选择星期）", "按月（同一日期）", "按年（同一日期）",
            "月内第几个星期几", "中国工作日", "大小周", "自定义轮班" };
        new AlertDialog.Builder(context).setTitle("重复").setItems(labels, (dialog, index) -> {
            Plan draft = Wire.copy(source, Plan.class);
            if (index == 0) {
                draft.rule = Rule.DAYS;
                draft.interval = 1;
                draft.count = 1;
                draft.until = "";
                accept.accept(draft);
            } else {
                if (CalendarEditing.once(source)) {
                    draft.count = 0;
                    draft.until = "";
                }
                draft.rule = RULES[index - 1];
                options(context, draft, accept);
            }
        }).setNegativeButton("取消", null).show();
    }

    private static void options(Context context, Plan draft, Consumer<Plan> accept) {
        LinearLayout panel = EditorControls.panel(context);
        LinearLayout row = CalendarScreen.row(panel);
        NumberPicker interval = CalendarDateDialog.picker(row, "每隔", 1, 366, draft.interval);
        CheckBox[] weekdays = weekdays(panel, draft);
        LinearLayout monthly = CalendarScreen.row(panel);
        NumberPicker nth = CalendarDateDialog.picker(monthly, "第几个", 1, 5, draft.nth);
        NumberPicker weekday = CalendarDateDialog.picker(monthly, "星期（7为周日）", 1, 7, draft.weekday);
        monthly.setVisibility(draft.rule == Rule.MONTH_NTH ? android.view.View.VISIBLE : android.view.View.GONE);
        CheckBox single = new CheckBox(context);
        single.setText("起始周为单休周（周日休息）");
        single.setChecked(draft.anchorSingleRest);
        panel.addView(single);
        single.setVisibility(draft.rule == Rule.ALTERNATING ? android.view.View.VISIBLE : android.view.View.GONE);
        LinearLayout rotationGroup = EditorControls.panel(context);
        panel.addView(rotationGroup);
        EditText rotation = EditorControls.field(rotationGroup, "轮班（1工作、0休息）", draft.rotation);
        rotationGroup.setVisibility(draft.rule == Rule.ROTATION ? android.view.View.VISIBLE : android.view.View.GONE);
        boolean intervalUsed = draft.rule != Rule.WORKDAYS && draft.rule != Rule.ALTERNATING
                && draft.rule != Rule.ROTATION;
        row.setVisibility(intervalUsed ? android.view.View.VISIBLE : android.view.View.GONE);
        ScheduleUi.text(panel, "月度日期不存在时跳过该月；2月29日按年重复时跳过非闰年。排班覆盖继续生效。", 13);
        EditorControls.confirm(context, "自定义重复", panel, () -> {
            draft.interval = interval.getValue();
            draft.weekdays = weekdayMask(weekdays);
            draft.nth = nth.getValue();
            draft.weekday = weekday.getValue();
            draft.anchorSingleRest = single.isChecked();
            draft.rotation = rotation.getText().toString().trim();
            com.canace.x3schedule.domain.Models.require(draft.weekdays != 0, "至少选择一个星期");
            com.canace.x3schedule.domain.Models.require(draft.rotation.matches("[01]{1,366}")
                    && draft.rotation.contains("1"), "轮班需要1至366天，至少一个工作日");
            accept.accept(draft);
        });
    }

    private static CheckBox[] weekdays(LinearLayout panel, Plan draft) {
        LinearLayout group = EditorControls.panel(panel.getContext());
        panel.addView(group);
        group.setVisibility(draft.rule == Rule.WEEKS ? android.view.View.VISIBLE : android.view.View.GONE);
        CheckBox[] fields = new CheckBox[7];
        for (int n = 0; n < 7; n++) {
            fields[n] = new CheckBox(panel.getContext());
            fields[n].setText(panel.getContext().getString(R.string.schedule_weekday_label,
                    Character.toString("一二三四五六日".charAt(n))));
            fields[n].setChecked((draft.weekdays & (1 << n)) != 0);
            group.addView(fields[n]);
        }
        return fields;
    }

    private static int weekdayMask(CheckBox[] fields) {
        int mask = 0;
        for (int n = 0; n < fields.length; n++) {
            if (fields[n].isChecked()) {
                mask |= 1 << n;
            }
        }
        return mask;
    }

    static void end(Context context, Plan source, Consumer<Plan> accept) {
        new AlertDialog.Builder(context).setTitle("结束重复").setItems(new String[] { "永不", "在日期结束", "达到次数" },
                (dialog, index) -> {
                    Plan draft = Wire.copy(source, Plan.class);
                    if (index == 0) {
                        draft.count = 0;
                        draft.until = "";
                        accept.accept(draft);
                    } else if (index == 1) {
                        LocalDate initial = LocalDate.parse(source.until.isEmpty() ? source.anchor : source.until);
                        CalendarDateDialog.show(context, "结束重复日期", initial.atStartOfDay(), true, date -> {
                            draft.until = date.toLocalDate().toString();
                            draft.count = 0;
                            EditorControls.safe(context, () -> {
                                com.canace.x3schedule.domain.Models.require(!date.toLocalDate()
                                        .isBefore(LocalDate.parse(source.anchor)), "结束重复不能早于开始日期");
                                accept.accept(draft);
                            });
                        });
                    } else {
                        count(context, draft, accept);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private static void count(Context context, Plan draft, Consumer<Plan> accept) {
        LinearLayout panel = EditorControls.panel(context);
        EditText count = EditorControls.field(panel, "重复次数", Integer.toString(Math.max(2, draft.count)));
        count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditorControls.confirm(context, "结束重复", panel, () -> {
            int value = Integer.parseInt(count.getText().toString());
            com.canace.x3schedule.domain.Models.require(value >= 2 && value <= 100000, "重复次数应为2至100000");
            draft.count = value;
            draft.until = "";
            accept.accept(draft);
        });
    }
}
