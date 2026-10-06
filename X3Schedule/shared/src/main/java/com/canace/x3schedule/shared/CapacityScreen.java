package com.canace.x3schedule.shared;

import android.app.Activity;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.canace.x3schedule.domain.Capacity;
import com.canace.x3schedule.domain.Wire;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only visibility into the next full snapshot, independent of delivery ACKs. */
final class CapacityScreen {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-capacity"));

    private final Activity activity;
    private final LinearLayout content;

    CapacityScreen(Activity activity, LinearLayout content) {
        this.activity = activity;
        this.content = content;
    }

    static void summary(Activity activity, TextView label) {
        Context context = activity.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Capacity.Usage usage = read(context);
                label.post(() -> {
                    if (active(activity, label)) {
                        label.setText(usage.status());
                    }
                });
            } catch (RuntimeException error) {
                label.post(() -> {
                    if (active(activity, label)) {
                        label.setText("容量超限或暂未读取，请查看数据容量页");
                    }
                });
            }
        });
    }

    private static boolean active(Activity activity, android.view.View view) {
        return !activity.isDestroyed() && !activity.isFinishing() && view.isAttachedToWindow();
    }

    private static Capacity.Usage read(Context context) {
        Wire.Snapshot snapshot;
        try (Store store = new Store(context)) {
            store.getReadableDatabase().beginTransaction();
            try {
                snapshot = store.snapshot(false);
                store.getReadableDatabase().setTransactionSuccessful();
            } finally {
                store.getReadableDatabase().endTransaction();
            }
        }
        // Counting outside the transaction releases the database before traversing all JSON fields.
        return Capacity.measureBatch(snapshot);
    }

    void show() {
        TextView result = text("正在读取下一份同步数据的容量…");
        Button refresh = new Button(activity);
        ScheduleUi.button(refresh);
        refresh.setText("刷新容量");
        content.addView(refresh);
        refresh.setOnClickListener(view -> load(result, refresh));
        text("显示本机下一份完整同步快照，不是已发送进度或设备剩余存储。"
                + "手表只统计本机要发出的执行记录；同步与手机分块备份各有 32 MiB 上限。");
        text("到 80% 提示接近上限。删除计划会保留历史，不一定降低占用；同步按 128 KiB 分批，收齐后整份合并；没有自动归档。");
        load(result, refresh);
    }

    private void load(TextView result, Button refresh) {
        refresh.setEnabled(false);
        Context context = activity.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Capacity.Usage usage = read(context);
                result.post(() -> {
                    if (active(activity, content)) {
                        refresh.setEnabled(true);
                        result.setText(description(usage));
                    }
                });
            } catch (RuntimeException error) {
                result.post(() -> {
                    if (active(activity, content)) {
                        refresh.setEnabled(true);
                        result.setText(R.string.schedule_capacity_error);
                    }
                });
            }
        });
    }

    private String description(Capacity.Usage usage) {
        String detail = usage.status() + "\n数据 " + String.format(Locale.ROOT, "%.1f", usage.bytes / 1024.0)
                + " / 32768 KiB\n计划 " + usage.plans + " / 1000\n实例 " + usage.instances
                + " / 50000\n执行记录 " + usage.events + " / 50000";
        if (usage.exceeded()) {
            detail += "\n当前批次超过上限。本地记录仍保留，"
                    + "不要通过卸载或清空数据处理容量问题。";
        }
        return detail;
    }

    private TextView text(String value) {
        return ScheduleUi.text(content, value, ScheduleUi.phone(activity) ? 16 : 14);
    }
}
