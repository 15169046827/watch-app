package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3schedule.domain.Backup;
import com.canace.x3schedule.domain.Wire;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local document work owns a separate database handle and never runs on the UI thread. */
final class BackupScreen {
    static void drainForMaintenance(Runnable drained) {
        WORKER.execute(() -> new android.os.Handler(android.os.Looper.getMainLooper()).post(drained));
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-backup"));
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final Activity activity;
    private final Context context;
    private final LinearLayout content;
    private final TextView status;

    BackupScreen(Activity activity, LinearLayout content) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.content = content;
        status = new TextView(activity);
        ScheduleUi.text(status, 16);
        content.addView(status);
    }

    void export(Uri uri) {
        run("正在准备和写入备份，请保持应用打开…", () -> {
            Wire.Snapshot snapshot = snapshot();
            try (OutputStream raw = context.getContentResolver().openOutputStream(uri, "wt")) {
                require(raw != null, "无法写入文件");
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    Backup.write(snapshot, out);
                }
            }
            return "备份已导出。文件含私人日程与记录，请妥善保管。";
        }, null, "导出未完成，请重新导出；不完整文件不能用于恢复。");
    }

    void preview(Uri uri) {
        run("正在读取并校验备份，请稍候…", () -> {
            Wire.Snapshot snapshot;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                require(in != null, "无法读取文件");
                snapshot = Backup.read(in);
            }
            activity.runOnUiThread(() -> {
                if (active()) {
                    showPreview(snapshot);
                }
            });
            return null;
        }, null, "备份读取或校验失败，未改动现有数据。");
    }

    private Wire.Snapshot snapshot() {
        try (Store store = new Store(context)) {
            require(store.phone, "备份只在手机进行");
            store.getReadableDatabase().beginTransaction();
            try {
                Wire.Snapshot snapshot = store.snapshot(true);
                store.getReadableDatabase().setTransactionSuccessful();
                return snapshot;
            } finally {
                store.getReadableDatabase().endTransaction();
            }
        }
    }

    private void showPreview(Wire.Snapshot snapshot) {
        status.setText(activity.getString(R.string.schedule_restore_summary, snapshot.plans.size(),
                snapshot.instances.size(), snapshot.events.size()));
        Button confirm = new Button(activity);
        ScheduleUi.button(confirm);
        ScheduleUi.primary(confirm);
        confirm.setText("确认合并恢复");
        content.addView(confirm);
        confirm.setOnClickListener(view -> restore(snapshot, confirm));
    }

    private void restore(Wire.Snapshot snapshot, Button confirm) {
        run("正在合并恢复，请保持应用打开…", () -> {
            try (Store store = new Store(context)) {
                store.restore(snapshot);
            }
            try {
                ReminderReceiver.schedule(context, false);
            } catch (RuntimeException error) {
                return "数据已恢复；提醒安排未完成，请重新打开应用检查提醒权限。";
            }
            return "合并恢复完成，返回首页可查看日程。";
        }, confirm, "合并恢复失败，未提交本次恢复，请检查冲突或存储空间。");
    }

    private void run(String message, Operation operation, Button confirm, String failure) {
        if (DataMaintenance.active()) {
            status.setText("正在处理本机数据，请稍候。");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            status.setText("已有备份操作正在进行，请稍后从备份页重试。");
            return;
        }
        status.setText(message);
        if (confirm != null) {
            confirm.setEnabled(false);
        }
        WORKER.execute(() -> {
            String result;
            boolean success;
            try {
                result = operation.perform();
                success = true;
            } catch (IOException | RuntimeException error) {
                result = failure;
                if (error instanceof IllegalArgumentException && error.getMessage() != null) {
                    result += "\n" + error.getMessage();
                }
                success = false;
            } finally {
                BUSY.set(false);
            }
            complete(result, success, confirm);
        });
    }

    private void complete(String result, boolean success, Button confirm) {
        activity.runOnUiThread(() -> {
            if (result != null) {
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                if (active()) {
                    status.setText(result);
                }
            }
            if (!success && confirm != null && active()) {
                confirm.setEnabled(true);
            }
        });
    }

    private boolean active() {
        return !activity.isDestroyed() && !activity.isFinishing() && status.isAttachedToWindow();
    }

    private interface Operation {
        String perform() throws IOException;
    }
}
