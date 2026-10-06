package com.canace.x3schedule.shared;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.canace.x3schedule.domain.Agenda;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** A coherent home snapshot without reading the entire history on the UI thread. */
final class AgendaScreen {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-agenda"));

    private AgendaScreen() {
    }

    static void load(Activity activity, LinearLayout parent, Consumer<Agenda> render) {
        TextView loading = ScheduleUi.text(parent, "正在整理本机日程…", 14);
        Context context = activity.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Agenda agenda = read(context);
                parent.post(() -> {
                    if (active(activity, parent)) {
                        parent.removeView(loading);
                        render.accept(agenda);
                    }
                });
            } catch (RuntimeException error) {
                parent.post(() -> {
                    if (active(activity, parent)) {
                        loading.setText("日程暂未读取，请返回后重试；本机记录保留。");
                    }
                });
            }
        });
    }

    private static boolean active(Activity activity, LinearLayout parent) {
        return !activity.isDestroyed() && !activity.isFinishing() && parent.isAttachedToWindow();
    }

    private static Agenda read(Context context) {
        try (Store store = new Store(context)) {
            store.getReadableDatabase().beginTransaction();
            try {
                Agenda agenda = Agenda.read(store.instances(), store.events(), System.currentTimeMillis(),
                        ZoneId.systemDefault());
                store.getReadableDatabase().setTransactionSuccessful();
                return agenda;
            } finally {
                store.getReadableDatabase().endTransaction();
            }
        }
    }
}
