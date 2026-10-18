package com.canace.x3schedule.shared;

import android.app.Activity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;
import com.canace.x3schedule.domain.Execution;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.ProgressReport;
import com.canace.x3schedule.domain.Reports;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phone report page. Database reads and aggregation run away from the UI thread.
 */
final class ReportScreen {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private final Activity activity;

    private final LinearLayout content;

    private EditText anchor;

    private EditText first;

    private EditText last;

    private EditText category;

    private Spinner period;

    private LinearLayout result;

    private Button submit;

    private boolean requested;
    private int generation;

    private Runnable afterRender = () -> { };

    ReportScreen(Activity activity, LinearLayout content) {
        this.activity = activity;
        this.content = content;
    }

    void show() {
        text(content, "统计时区：中国标准时间。只统计已开始、已保存的执行型实例；仅提醒型和待办不计完成率。", 14);
        period = new Spinner(activity);
        ScheduleUi.choice(period);
        period.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "日报", "周报（周一至周日）", "月报", "年报", "自定义" }));
        period.setContentDescription("报告周期");
        content.addView(period);
        LocalDate today = LocalDate.now(REPORT_ZONE);
        anchor = field("参考日期（所在日 / 周 / 月 / 年）", today.toString());
        first = field("自定义开始日期", today.withDayOfMonth(1).toString());
        last = field("自定义结束日期（含当天）", today.toString());
        DateTimeFields.date(anchor, false);
        DateTimeFields.date(first, false);
        DateTimeFields.date(last, false);
        category = field("大类名称（空为全部）", "");
        period.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean custom = position == 4;
                anchor.setEnabled(!custom);
                first.setEnabled(custom);
                last.setEnabled(custom);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // The adapter always contains the five supported choices.
            }
        });
        period.setSelection(2);
        submit = new Button(activity);
        ScheduleUi.button(submit);
        ScheduleUi.primary(submit);
        submit.setText("查看报告");
        content.addView(submit);
        result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        content.addView(result);
        submit.setOnClickListener(view -> request());
    }

    private EditText field(String label, String initial) {
        text(content, label, 14);
        EditText input = new EditText(activity);
        ScheduleUi.field(input);
        input.setText(initial);
        input.setContentDescription(label);
        input.setSingleLine(true);
        content.addView(input);
        return input;
    }

    private String value(EditText input) {
        return input.getText().toString().trim();
    }

    boolean requested() {
        return requested;
    }

    void reload(Runnable complete) {
        afterRender = complete;
        request();
    }

    private void request() {
        try {
            int selected = period.getSelectedItemPosition();
            Reports.Range range = selected == 4 ? new Reports.Range(LocalDate.parse(value(first)), LocalDate
                    .parse(value(last)), REPORT_ZONE) : Reports.range(Reports.Period.values()[selected], LocalDate
                    .parse(value(anchor)), REPORT_ZONE);
            String filter = value(category).isEmpty() ? null : value(category);
            requested = true;
            submit.setEnabled(false);
            result.removeAllViews();
            text(result, "正在整理本地记录…", 14);
            int request = ++generation;
            new Thread(() -> load(range, filter, request), "schedule-report").start();
        } catch (DateTimeParseException error) {
            Toast.makeText(activity, "日期请使用 YYYY-MM-DD 格式", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void load(Reports.Range range, String filter, int request) {
        try (Store store = new Store(activity.getApplicationContext())) {
            // One read transaction makes the three tables and settings a consistent snapshot.
            store.getReadableDatabase().beginTransaction();
            Reports.Summary summary;
            ProgressReport.Summary progress;
            try {
                List<Instance> instances = store.instances();
                List<Event> events = store.events();
                Map<String, Plan> plans = new HashMap<>();
                for (Plan plan : store.plans()) {
                    plans.put(plan.id, plan);
                }
                Set<String> leaves = Execution.validLeaves(instances, events, plans, store.settings());
                summary = Reports.summarize(instances, events, leaves, range, System.currentTimeMillis(), filter);
                List<Instance> graded = new java.util.ArrayList<>(instances);
                graded.removeIf(i -> filter != null && !filter.equals(i.category));
                progress = ProgressReport.range(graded, events, range.first,
                        range.last, System.currentTimeMillis());
                store.getReadableDatabase().setTransactionSuccessful();
            } finally {
                store.getReadableDatabase().endTransaction();
            }
            activity.runOnUiThread(() -> {
                if (request != generation || !active()) {
                    return;
                }
                render(range, summary);
                if (active()) {
                    progressReport(progress);
                }
            });
        } catch (RuntimeException error) {
            activity.runOnUiThread(() -> {
                if (active() && request == generation) {
                    submit.setEnabled(true);
                    result.removeAllViews();
                    text(result, "报告未能读取，请返回后重试；原记录保留。", 14);
                }
            });
        }
    }

    private boolean active() {
        return !activity.isDestroyed() && !activity.isFinishing() && content.isAttachedToWindow();
    }

    private void progressReport(ProgressReport.Summary summary) {
        ScheduleUi.heading(result, "等级任务 · 按各任务归属日");
        android.widget.TextView score = ScheduleUi.text(result, "计划执行得分："
                + ProgressReport.display(summary.score())
                + " · 待确认 " + summary.pending, 18);
        ProgressAppearance.score(score, summary.score());
        if (period.getSelectedItemPosition() <= 1) {
            text(result, "实际成果：完整 " + summary.perfect + " · 基本 " + summary.basic
                    + " · 最低行动 " + summary.minimum + " · 未完成/未进行 " + summary.none
                    + " · 豁免 " + summary.exempt, 14);
        }
        text(result, "正式补救启用 " + summary.recoveryEnabled + " · 有行动 " + summary.recoveryActed
                + " · 升档 " + summary.recoveryImproved + " · 最终完整 " + summary.recoveryPerfect
                + " · 自行补做 " + summary.manualActions, 14);
        text(result, "有效任务等权平均；未反馈、未来、纯提醒、不计分、有效请假/让位不作为零分。旧规则报表独立保留。", 13);
    }

    private void render(Reports.Range range, Reports.Summary summary) {
        if (!active()) {
            return;
        }
        submit.setEnabled(true);
        result.removeAllViews();
        text(result, range.first + " 至 " + range.last, 18);
        if (summary.total.planned == 0) {
            text(result, "该范围暂无已保存且已开始的日程记录。未来实例不计入，缺失历史不会补成未完成。", 14);
        }
        showReport("合计", summary.total);
        for (Map.Entry<String, Execution.Report> entry : summary.categories.entrySet()) {
            showReport("大类 · " + entry.getKey(), entry.getValue());
        }
        for (Reports.Group group : summary.plans.values()) {
            showReport(group.category + " / " + group.title, group.report);
        }
        text(result, "执行完成率和按时率以扣除有效请假的应执行项为分母；计划履约率保留原计划总数。" + "计划时长完成率以完成项的计划分钟数计算，并非实际耗时。"
                + "补录时间未知仅计完成；逾期未确认计入未完成并单列。", 13);
        afterRender.run();
        afterRender = () -> { };
    }

    private void showReport(String name, Execution.Report report) {
        text(result, name, 19);
        text(result, "计划 " + report.planned + " · 完成 " + report.completed + " · 进行中 " + report.running + "\n按时 "
                + report.onTime + " · 延迟 " + report.late + " · 完成时间未知 " + report.completedUnknown + "\n有效请假 "
                + report.leave + " · 超额请假 " + report.excessLeave + "\n未完成（含逾期未确认） " + report
                .incompleteIncludingOverdue() + "\n待确认 " + report.pending + " · 逾期未确认 " + report.overdue
                + "\n执行完成率 " + percent(report.executionRate()) + " · 计划履约率 " + percent(report.fulfillmentRate())
                + "\n按时率 " + percent(report.onTimeRate()) + " · 计划时长完成率 " + percent(report.plannedDurationRate())
                + "\n完成项计划分钟 " + report.completedMinutes + " / " + report.plannedMinutes, 14);
    }

    private String percent(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.0f%%", value * 100);
    }

    private void text(LinearLayout parent, String value, int size) {
        ScheduleUi.text(parent, value, size);
    }
}
