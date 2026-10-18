package com.canace.x3schedule.shared;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.RecoveryConflicts;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/** Acknowledged conflicts remain inspectable; an enabled commitment cannot be silently invalidated. */
final class RecoveryConflictsScreen {
    private final Activity activity;
    private final LinearLayout parent;
    private final Consumer<Instance> detail;
    private android.view.View pageMarker;

    RecoveryConflictsScreen(Activity activity, LinearLayout parent, Consumer<Instance> detail) {
        this.activity = activity;
        this.parent = parent;
        this.detail = detail;
    }

    void show() {
        parent.removeAllViews();
        ScheduleUi.heading(parent, "未来七天补救冲突");
        pageMarker = parent.getChildAt(0);
        android.widget.TextView marker = ScheduleUi.text(parent, "正在检查…", 14);
        new Thread(() -> {
            try (Store store = new Store(activity.getApplicationContext())) {
                List<RecoveryConflicts.Conflict> conflicts = RecoveryConflicts.scan(store.instances(),
                        store.events(), System.currentTimeMillis());
                activity.runOnUiThread(() -> {
                    if (marker.getParent() == parent && !activity.isDestroyed()) {
                        parent.removeView(marker);
                        render(conflicts);
                    }
                });
            } catch (RuntimeException failure) {
                activity.runOnUiThread(() -> marker.setText("冲突清单未能读取，请重新打开。"));
            }
        }, "schedule-recovery-conflicts").start();
    }

    private void render(List<RecoveryConflicts.Conflict> conflicts) {
        ScheduleUi.text(parent, "每日按默认提醒时刻汇总。静音只针对当日同一组冲突，时间或冲突范围变化会重新提示。", 14);
        if (conflicts.isEmpty()) {
            ScheduleUi.text(parent, "未来七天没有未解决的备用补救冲突。更远的重复日程会滚动检查。", 15);
        }
        for (RecoveryConflicts.Conflict conflict : conflicts) {
            ScheduleUi.heading(parent, conflict.item.title + " · " + conflict.item.date);
            for (com.canace.x3schedule.domain.RecoveryPlanner.Impact impact : conflict.impacts) {
                ScheduleUi.text(parent, impact.label(), 14);
            }
            ScheduleUi.text(parent, conflict.committed ? "已承诺补救，必须处理"
                    : conflict.blocked ? "已选择当日备用不可启用" : conflict.acknowledged ? "已知冲突，通知静音" : "待处理", 13);
            ScheduleUi.button(parent, "打开任务，调整日程或补救", () -> detail.accept(conflict.item));
            if (!conflict.committed) {
                ScheduleUi.button(parent, "只改当日备用时段", () -> standby(conflict));
                ScheduleUi.button(parent, "暂不处理，当日备用不可启用", () ->
                        decide(conflict, Progress.Op.BLOCK_STANDBY, 0, 0));
                ScheduleUi.button(parent, "已知冲突，不再通知", () ->
                        decide(conflict, Progress.Op.ACK_CONFLICT, 0, 0));
            }
        }
    }

    private void standby(RecoveryConflicts.Conflict conflict) {
        ZoneId zone = ZoneId.of(conflict.item.zone);
        java.time.LocalDateTime start = Instant.ofEpochMilli(conflict.start).atZone(zone).toLocalDateTime();
        CalendarDateDialog.show(activity, "当日备用补救开始", start, false, selected ->
                CalendarDateDialog.show(activity, "当日备用补救结束",
                        selected.plusMinutes(conflict.item.progress.recoveryDuration), false, end ->
                                decide(conflict, Progress.Op.STANDBY,
                                        selected.atZone(zone).toInstant().toEpochMilli(),
                                        end.atZone(zone).toInstant().toEpochMilli())));
    }

    private void decide(RecoveryConflicts.Conflict conflict, Progress.Op op, long start, long end) {
        Progress.Entry entry = new Progress.Entry();
        entry.op = op;
        entry.start = start;
        entry.end = end;
        entry.conflictKey = conflict.key;
        android.view.View owner = pageMarker;
        new Thread(() -> {
            String message = null;
            try (Store store = new Store(activity.getApplicationContext())) {
                store.recordProgress(conflict.item, entry);
                ReminderWork.request(activity.getApplicationContext(), false, success -> { });
            } catch (RuntimeException failure) {
                message = failure.getMessage();
            }
            String error = message;
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed() && parent.isAttachedToWindow() && owner.getParent() == parent) {
                    if (error == null) {
                        show();
                    } else {
                        Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "schedule-recovery-decision").start();
    }
}
