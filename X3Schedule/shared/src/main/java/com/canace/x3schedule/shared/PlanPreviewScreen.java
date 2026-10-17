package com.canace.x3schedule.shared;

import android.app.Activity;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3schedule.domain.Kinds;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import com.canace.x3schedule.domain.OverlapIndex;
import com.canace.x3schedule.domain.PlanEdits;
import com.canace.x3schedule.domain.RecoveryConflicts;
import com.canace.x3schedule.domain.Recurrence;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only preview work is asynchronous; the user must still explicitly confirm saving. */
final class PlanPreviewScreen {
    static void drainForMaintenance(Runnable drained) {
        REQUESTS.incrementAndGet();
        WORKER.execute(() -> new android.os.Handler(android.os.Looper.getMainLooper()).post(drained));
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-preview"));
    private static final AtomicLong REQUESTS = new AtomicLong();
    private final Activity activity;
    private final LinearLayout parent;
    private final List<Plan> drafts;
    private final Runnable saved;
    private final Runnable loaded;
    private java.util.Set<String> acceptedConflicts = java.util.Collections.emptySet();
    private boolean blockStandby;

    PlanPreviewScreen(Activity activity, LinearLayout parent, List<Plan> drafts, Runnable saved, Runnable loaded) {
        this.activity = activity;
        this.parent = parent;
        this.drafts = drafts;
        this.saved = saved;
        this.loaded = loaded;
    }

    void show() {
        TextView loading = ScheduleUi.text(parent, "正在生成预览和检查重叠…", 14);
        Context context = activity.getApplicationContext();
        long request = REQUESTS.incrementAndGet();
        WORKER.execute(() -> {
            if (request != REQUESTS.get()) {
                return;
            }
            try {
                Preview result = read(context);
                parent.post(() -> {
                    if (active() && request == REQUESTS.get()) {
                        parent.removeView(loading);
                        render(result);
                        parent.post(loaded);
                    }
                });
            } catch (RuntimeException error) {
                parent.post(() -> {
                    if (active()) {
                        loading.setText("预览未能生成，尚未保存。请返回修改或重新打开预览。");
                    }
                });
            }
        });
    }

    private boolean active() {
        return !activity.isDestroyed() && !activity.isFinishing() && parent.isAttachedToWindow();
    }

    private Preview read(Context context) {
        List<Instance> existing;
        List<com.canace.x3schedule.domain.Models.Event> events;
        Settings settings;
        try (Store store = new Store(context)) {
            store.getReadableDatabase().beginTransaction();
            try {
                existing = store.instances();
                settings = store.settings();
                events = store.events();
                store.getReadableDatabase().setTransactionSuccessful();
            } finally {
                store.getReadableDatabase().endTransaction();
            }
        }
        OverlapIndex overlaps = new OverlapIndex(existing);
        Preview result = new Preview();
        java.util.Map<String, Instance> projected = new java.util.HashMap<>();
        for (Instance item : existing) {
            if (drafts.stream().noneMatch(p -> p.id.equals(item.planId)) || item.start <= System.currentTimeMillis()) {
                projected.put(item.id, item);
            }
        }
        for (Plan draft : drafts) {
            LocalDate today = LocalDate.now(ZoneId.of(draft.zone));
            List<Instance> generated = Recurrence.generate(PlanEdits.withDefaults(draft, settings),
                    today, today.plusDays(180));
            for (Instance item : generated) {
                result.conflicts += overlaps.count(item);
                projected.putIfAbsent(item.id, item);
            }
            result.samples.add(new ArrayList<>(generated.subList(0, Math.min(8, generated.size()))));
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (RecoveryConflicts.Conflict conflict
                : RecoveryConflicts.scan(existing, events, System.currentTimeMillis())) {
            known.add(conflict.key);
        }
        for (RecoveryConflicts.Conflict conflict
                : RecoveryConflicts.scan(projected.values(), events,
                        System.currentTimeMillis())) {
            if (!known.contains(conflict.key)) {
                result.standby.add(conflict);
            }
        }
        return result;
    }

    private void render(Preview result) {
        for (int n = 0; n < Math.min(20, drafts.size()); n++) {
            Plan plan = drafts.get(n);
            ScheduleUi.text(parent, Kinds.label(plan.kind) + " · " + plan.category + " / " + plan.title, 19);
            List<Instance> sample = result.samples.get(n);
            for (Instance item : sample) {
                ScheduleUi.text(parent, CalendarScreen.label(item, ZoneId.of(plan.zone)), 14);
            }
            if (sample.isEmpty()) {
                ScheduleUi.text(parent, "未来180天无实例；历史开始时间不会自动补发提醒。", 14);
            }
        }
        if (drafts.size() > 20) {
            ScheduleUi.text(parent, "共" + drafts.size() + "个计划，下方入口可核对完整计划列表。", 14);
            ScheduleUi.button(parent, "查看全部预览计划", () -> allPlans(result));
        }
        ScheduleUi.text(parent, "未来180天与已有其他计划重叠：" + result.conflicts
                + "处；仍可保存。已开始的历史及待办完成状态保留。", 14);
        Button confirm = ScheduleUi.button(parent, "确认保存", () -> { });
        confirm.setOnClickListener(view -> consent(confirm, result));
        ScheduleUi.primary(confirm);
    }

    private void consent(Button button, Preview preview) {
        if (preview.standby.isEmpty()) {
            commit(button);
            return;
        }
        StringBuilder details = new StringBuilder("以下备用补救有冲突，启用时仍会重新检查：\n");
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RecoveryConflicts.Conflict conflict : preview.standby) {
            details.append(conflict.item.title).append(" · ").append(conflict.item.date).append('\n');
            keys.add(conflict.key);
        }
        new android.app.AlertDialog.Builder(activity).setTitle("处理补救冲突").setMessage(details)
                .setNegativeButton("返回调整日程", null)
                .setNeutralButton("已知冲突，不再通知", (dialog, which) -> {
                    acceptedConflicts = keys;
                    blockStandby = false;
                    commit(button);
                }).setPositiveButton("暂不处理，当日备用补救失效", (dialog, which) -> {
                    acceptedConflicts = keys;
                    blockStandby = true;
                    commit(button);
                }).show();
    }

    private void commit(Button confirm) {
        if (DataMaintenance.active()) {
            return;
        }
        confirm.setEnabled(false);
        confirm.setText(R.string.schedule_saving);
        Context context = activity.getApplicationContext();
        WORKER.execute(() -> {
            try (Store store = new Store(context)) {
                List<Plan> copies = new ArrayList<>();
                for (Plan draft : drafts) {
                    copies.add(Wire.copy(draft, Plan.class));
                }
                store.saveWithConflictConsent(copies, acceptedConflicts, blockStandby);
            } catch (RuntimeException error) {
                parent.post(() -> failed(confirm));
                return;
            }
            boolean reminders = schedule(context);
            parent.post(() -> {
                if (active()) {
                    if (!reminders) {
                        Toast.makeText(activity, "日程已保存，提醒未能重新安排；请检查提醒权限。", Toast.LENGTH_LONG).show();
                    }
                    saved.run();
                }
            });
        });
    }

    private void failed(Button confirm) {
        if (active()) {
            confirm.setEnabled(true);
            confirm.setText(R.string.schedule_confirm_save);
            Toast.makeText(activity, "保存未完成，原数据保留；请检查空间或重新打开预览。", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean schedule(Context context) {
        try {
            ReminderReceiver.schedule(context, false);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void allPlans(Preview result) {
        String[] labels = new String[drafts.size()];
        for (int n = 0; n < labels.length; n++) {
            labels[n] = drafts.get(n).title + " · " + drafts.get(n).category;
        }
        new android.app.AlertDialog.Builder(activity).setTitle("全部预览计划")
                .setItems(labels, (dialog, index) -> CalendarItemDialog.show(activity, drafts.get(index).title,
                        result.samples.get(index), ZoneId.of(drafts.get(index).zone), item ->
                                new android.app.AlertDialog.Builder(activity).setTitle(item.title)
                                        .setMessage(CalendarScreen.label(item, ZoneId.of(item.zone)))
                                        .setPositiveButton("关闭", null).show()))
                .setNegativeButton("关闭", null).show();
    }

    private static final class Preview {
        final List<List<Instance>> samples = new ArrayList<>();
        long conflicts;
        final List<RecoveryConflicts.Conflict> standby = new ArrayList<>();
    }
}
