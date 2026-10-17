package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.ProgressHistory;
import com.canace.x3schedule.domain.RecoveryPlanner;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Phone and watch share feedback; timetable editing and manual recovery stay on the phone. */
final class ProgressScreen {
    private final Activity activity;
    private final LinearLayout section;
    private final Instance item;
    private final Runnable changed;
    private final AtomicBoolean saving = new AtomicBoolean();
    private boolean phone;
    private List<Event> events;
    private List<Instance> instances;
    private Progress.Summary state;

    ProgressScreen(Activity activity, LinearLayout parent, Instance item, Runnable changed) {
        this.activity = activity;
        this.item = item;
        this.changed = changed;
        section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        parent.addView(section);
    }

    void show() {
        ScheduleUi.text(section, "正在读取任务进展…", 14);
        new Thread(() -> {
            try (Store store = new Store(activity.getApplicationContext())) {
                phone = store.phone;
                events = store.events();
                instances = store.instances();
                state = Progress.summarize(item, events);
                activity.runOnUiThread(() -> {
                    if (active()) {
                        render();
                    }
                });
            } catch (RuntimeException failure) {
                activity.runOnUiThread(() -> {
                    if (active()) {
                        section.removeAllViews();
                        ScheduleUi.text(section, "任务进展未能读取，请重新打开。", 14);
                    }
                });
            }
        }, "schedule-progress").start();
    }

    private boolean active() {
        return !activity.isDestroyed() && !activity.isFinishing() && section.isAttachedToWindow();
    }

    private void render() {
        section.removeAllViews();
        ScheduleUi.status(section, Progress.describe(state));
        ScheduleUi.text(section, "任务日 " + item.taskDay + " · " + (item.progress.untimed ? "不定时，无失约修正"
                : "原时段与已启用补救分别记录承诺") + "\n补录截止 " + time(Progress.expiry(item)), 13);
        if (state.grade != null && !state.exempt()) {
            ScheduleUi.text(section, "实际成果：" + Progress.achievement(state) + "\n计划执行得分："
                    + (item.progress.scored ? Progress.score(state.grade, state.deviations()) + "分" : "不计分"), 16);
        }
        ScheduleUi.text(section, "实际成果可继续提高；已确认的未进行/完全没有补救不可撤销失约。"
                + "选择相同累计等级也表示本次有行动。", 13);
        long now = System.currentTimeMillis();
        if (now < item.start || now >= Progress.expiry(item)) {
            ScheduleUi.text(section, now < item.start ? "尚未开始" : "补录期限已结束", 14);
            return;
        }
        feedback();
        if (phone) {
            ScheduleUi.button(section, "更正误选的成果（保留原始记录）", this::corrections);
        }
        recovery();
    }

    private void feedback() {
        if (state.original == null) {
            ScheduleUi.button(section, item.progress.untimed ? "反馈今日成果" : "反馈原时段行动与成果",
                    () -> grades(Progress.Session.ORIGINAL));
        } else if (phone) {
            ScheduleUi.button(section, "自行补做 / 补录最终成果", () -> grades(Progress.Session.MANUAL));
        }
        if ((state.enabled || state.recoveryConflict) && System.currentTimeMillis() >= state.recoveryEnd
                && !state.recoveryLeave
                && !state.scheduledActed && !state.recoveryMiss) {
            ScheduleUi.button(section, "反馈正式补救后的累计成果", this::scheduledFeedback);
        }
        ScheduleUi.button(section, "原时段请假（按额度核验）", () -> commit(entry(Progress.Op.LEAVE,
                Progress.Session.ORIGINAL)));
        if (state.enabled) {
            ScheduleUi.button(section, "补救时段请假", () -> commit(entry(Progress.Op.LEAVE, Progress.Session.SCHEDULED)));
        }
    }

    private void scheduledFeedback() {
        if (!state.recoveryConflict) {
            grades(Progress.Session.SCHEDULED);
        } else if (phone) {
            new AlertDialog.Builder(activity).setTitle("核对两端离线补救记录")
                    .setMessage("两端曾离线安排不同补救时段，全部原始记录均保留。当前采用 "
                            + time(state.recoveryStart) + " – " + time(state.recoveryEnd)
                            + "，仅算一次正式补救。请核对后如实反馈；确认后解除统计待定状态。")
                    .setNegativeButton("返回核对", null)
                    .setPositiveButton("已核对，反馈成果", (dialog, which) -> grades(Progress.Session.SCHEDULED)).show();
        } else {
            Toast.makeText(activity, "存在两端离线安排冲突，请前往手机核对后反馈。", Toast.LENGTH_LONG).show();
        }
    }

    private void grades(Progress.Session session) {
        grades(session, "");
    }

    private void grades(Progress.Session session, String replaces) {
        LinearLayout panel = EditorControls.panel(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("请选择累计成果")
                .setView(wrap(panel)).setNegativeButton("取消", null).create();
        for (Progress.Grade grade : Progress.Grade.values()) {
            if (!offerGrade(grade, session, replaces)) {
                continue;
            }
            String label = !item.progress.graded() && grade == Progress.Grade.NONE ? "— 未完成（0分）"
                    : Progress.label(grade);
            Button button = ScheduleUi.button(panel, label + standard(grade), () -> {
                dialog.dismiss();
                Progress.Entry result = entry(replaces.isEmpty() ? Progress.Op.RESULT : Progress.Op.CORRECT, session);
                result.replacesId = replaces;
                result.grade = grade;
                result.acted = grade != Progress.Grade.NONE;
                confirm(result);
            });
            ProgressAppearance.choice(button, grade);
        }
        if (session == Progress.Session.SCHEDULED && replaces.isEmpty()) {
            ScheduleUi.button(panel, "完全没有补救（保留已有成果）", () -> {
                dialog.dismiss();
                Progress.Entry result = entry(Progress.Op.RESULT, session);
                result.grade = state.grade == null ? Progress.Grade.NONE : state.grade;
                result.acted = false;
                confirm(result);
            });
        }
        dialog.show();
    }

    private boolean offerGrade(Progress.Grade grade, Progress.Session session, String replaces) {
        if (!item.progress.graded() && grade != Progress.Grade.NONE && grade != Progress.Grade.PERFECT) {
            return false;
        }
        if (!replaces.isEmpty() || session == Progress.Session.ORIGINAL) {
            return true;
        }
        return (grade != Progress.Grade.NONE || !item.progress.graded())
                && (state.grade == null || grade.ordinal() >= state.grade.ordinal());
    }

    private void corrections() {
        List<Event> targets = ProgressHistory.correctableResults(item.id, events);
        if (targets.isEmpty()) {
            Toast.makeText(activity, "没有可更正的记录；已确认未进行或完全没有补救不能撤销。", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[targets.size()];
        for (int n = 0; n < labels.length; n++) {
            Event event = targets.get(n);
            String grade = !event.snapshot.progress.graded() && event.progress.grade == Progress.Grade.NONE
                    ? "未完成（0分）" : Progress.label(event.progress.grade);
            labels[n] = time(event.confirmedAt) + " · " + grade
                    + " · " + (event.progress.session == Progress.Session.ORIGINAL ? "原时段"
                    : event.progress.session == Progress.Session.SCHEDULED ? "正式补救" : "自行补做");
        }
        new AlertDialog.Builder(activity).setTitle("更正误选，不表示后来补做")
                .setItems(labels, (dialog, which) -> {
                    Event target = targets.get(which);
                    grades(target.progress.session, target.id);
                }).setNegativeButton("取消", null).show();
    }

    private String standard(Progress.Grade grade) {
        switch (grade) {
            case PERFECT:
                return item.progress.graded() ? "\n" + item.progress.perfect : "";
            case BASIC:
                return "\n" + item.progress.basic;
            case MINIMUM:
                return "\n" + item.progress.minimum;
            default:
                return "";
        }
    }

    private void confirm(Progress.Entry entry) {
        if (!item.progress.graded() && entry.grade == Progress.Grade.NONE) {
            binaryAction(entry);
            return;
        }
        if (entry.acted) {
            commit(entry);
            return;
        }
        new AlertDialog.Builder(activity).setTitle("确认完全没有行动？")
                .setMessage(item.progress.untimed ? "本次记录保留，之后可通过自行补做提高成果，不产生定时失约。"
                        : "将保留本次失约，后续提高成果不能撤销。一次失约的完整完成得80分，两次得60分。")
                .setNegativeButton("返回", null).setPositiveButton("确认没有行动", (dialog, which) -> commit(entry)).show();
    }

    private void binaryAction(Progress.Entry entry) {
        AlertDialog.Builder prompt = new AlertDialog.Builder(activity).setTitle("未完成，但是否有行动？")
                .setMessage("未定义中间档标准，成果仍为0分。有行动不会记作失约；只有明确完全没做才记录失约。")
                .setNegativeButton("返回", null)
                .setPositiveButton("做了但没完成", (dialog, which) -> {
                    entry.acted = true;
                    commit(entry);
                });
        if (entry.session != Progress.Session.MANUAL) {
            prompt.setNeutralButton("确认完全没做", (dialog, which) -> {
                entry.acted = false;
                if (entry.session == Progress.Session.SCHEDULED && state.grade != null) {
                    entry.grade = state.grade;
                }
                commit(entry);
            });
        }
        prompt.show();
    }

    private void recovery() {
        if (state.recoveryConflict) {
            ScheduleUi.text(section, "存在两端离线补救安排冲突，请在手机核对。最早时段开始后不能取消或重排；"
                    + "未解决前暂不计入执行得分；结束后可核对当前时段并反馈，原始记录全部保留。", 14);
        }
        if (state.enabled || state.recoveryConflict) {
            ScheduleUi.text(section, "↻ 正式补救 " + time(state.recoveryStart) + " – " + time(state.recoveryEnd), 15);
            if (phone && System.currentTimeMillis() < state.earliestRecoveryStart) {
                ScheduleUi.button(section, "取消本次补救承诺", () -> commit(entry(Progress.Op.CANCEL,
                        Progress.Session.SCHEDULED)));
                ScheduleUi.button(section, "重新安排本次补救", this::reschedule);
            }
        } else if (item.progress.recoveryMinute >= 0 && state.original != null
                && state.grade != Progress.Grade.PERFECT) {
            ScheduleUi.button(section, "启用备用补救时段", () -> EditorControls.safe(activity, () -> {
                long[] window = RecoveryPlanner.standby(item, state);
                preview(window[0], window[1], Progress.Op.ENABLE);
            }));
        }
    }

    private void reschedule() {
        java.time.LocalDateTime start = Instant.ofEpochMilli(state.recoveryStart)
                .atZone(ZoneId.of(item.zone)).toLocalDateTime();
        CalendarDateDialog.show(activity, "补救开始", start, false, selected ->
                CalendarDateDialog.show(activity, "补救结束", selected.plusMinutes(item.progress.recoveryDuration),
                        false, end -> EditorControls.safe(activity, () -> preview(
                                selected.atZone(ZoneId.of(item.zone)).toInstant().toEpochMilli(),
                                end.atZone(ZoneId.of(item.zone)).toInstant().toEpochMilli(), Progress.Op.RESCHEDULE))));
    }

    private void preview(long start, long end, Progress.Op op) {
        List<RecoveryPlanner.Impact> impacts = RecoveryPlanner.preview(item, start, end, instances,
                events, System.currentTimeMillis());
        if (!phone && !impacts.isEmpty() || impacts.stream().anyMatch(i -> i.hard)) {
            new AlertDialog.Builder(activity).setTitle("补救时段未能开启")
                    .setMessage("存在日程冲突。请前往手机处理后重试。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        Progress.Entry entry = entry(op, Progress.Session.SCHEDULED);
        entry.start = start;
        entry.end = end;
        entry.conflictKey = RecoveryPlanner.fingerprint(item, start, end, impacts);
        LinearLayout panel = EditorControls.panel(activity);
        ScheduleUi.text(panel, time(start) + " – " + time(end), 16);
        List<android.widget.Switch> choices = new ArrayList<>();
        for (RecoveryPlanner.Impact impact : impacts) {
            ScheduleUi.text(panel, impact.label(), 14);
            android.widget.Switch yield = new android.widget.Switch(activity);
            yield.setText("确认本次让位豁免：" + impact.item.title);
            yield.setMinHeight(ScheduleUi.dp(activity, 48));
            panel.addView(yield);
            choices.add(yield);
        }
        EditorControls.confirm(activity, "确认补救与影响", panel, () -> {
            entry.yielded.clear();
            for (int n = 0; n < choices.size(); n++) {
                if (choices.get(n).isChecked()) {
                    entry.yielded.add(impacts.get(n).item.id);
                }
            }
            RecoveryPlanner.requireConsent(item, entry, impacts);
            commit(entry);
        });
    }

    private void commit(Progress.Entry entry) {
        if (!saving.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            String error = null;
            try (Store store = new Store(activity.getApplicationContext())) {
                store.recordProgress(item, entry);
                ReminderWork.request(activity.getApplicationContext(), false, success -> { });
            } catch (RuntimeException failure) {
                error = failure.getMessage();
            }
            String message = error;
            activity.runOnUiThread(() -> {
                saving.set(false);
                if (active()) {
                    if (message == null) {
                        changed.run();
                        suggestRecovery(entry);
                    } else {
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "schedule-progress-save").start();
    }

    private void suggestRecovery(Progress.Entry saved) {
        if (saved.op != Progress.Op.RESULT || saved.session != Progress.Session.ORIGINAL
                || saved.grade == Progress.Grade.PERFECT || item.progress.recoveryMinute < 0) {
            return;
        }
        long[] window = RecoveryPlanner.standby(item, state);
        if (window[0] <= System.currentTimeMillis() || window[1] > item.taskDayEnd) {
            return;
        }
        new AlertDialog.Builder(activity).setTitle("启用今天的补救时段？")
                .setMessage(time(window[0]) + " – " + time(window[1])
                        + "。启用时重新检查冲突，确认后才形成补救承诺。")
                .setNegativeButton("本次不启用", null)
                .setPositiveButton("预览并确认", (dialog, which) -> EditorControls.safe(activity,
                        () -> preview(window[0], window[1], Progress.Op.ENABLE))).show();
    }

    private static Progress.Entry entry(Progress.Op op, Progress.Session session) {
        Progress.Entry entry = new Progress.Entry();
        entry.op = op;
        entry.session = session;
        return entry;
    }

    private String time(long epoch) {
        return Instant.ofEpochMilli(epoch).atZone(ZoneId.of(item.zone))
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private android.widget.ScrollView wrap(LinearLayout panel) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(panel);
        return scroll;
    }
}
