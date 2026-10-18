package com.canace.x3schedule.shared;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Suggestion;
import com.canace.x3schedule.domain.Optimizer;
import com.canace.x3schedule.domain.PlanEdits;
import com.canace.x3schedule.domain.Recurrence;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Phone-only analysis and explicit preview; background results cannot update a detached page. */
final class SuggestionScreen {
    private final Activity activity;
    private final LinearLayout content;
    private final LinearLayout result;
    private Button refresh;

    SuggestionScreen(Activity activity, LinearLayout content) {
        this.activity = activity;
        this.content = content;
        result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
    }

    void show() {
        text(content, "仅分析最近 90 天、当前计划版本的已完成记录；缺失时间不推算。"
                + "同类偏差至少 3 次显示观察；至少 6 个有效样本且命中 60% 才达到建议门槛。");
        refresh = button(content, "分析本地记录", this::load);
        content.addView(result);
        load();
    }

    private boolean active() {
        return !activity.isDestroyed() && !activity.isFinishing() && content.isAttachedToWindow();
    }

    private void load() {
        refresh.setEnabled(false);
        result.removeAllViews();
        text(result, "正在分析本地记录…");
        new Thread(() -> {
            try (Store store = new Store(activity.getApplicationContext())) {
                store.getReadableDatabase().beginTransaction();
                List<Optimizer.Advice> advice;
                try {
                    advice = Optimizer.analyze(store.plans(), store.instances(), store.events(),
                            System.currentTimeMillis());
                    store.getReadableDatabase().setTransactionSuccessful();
                } finally {
                    store.getReadableDatabase().endTransaction();
                }
                activity.runOnUiThread(() -> render(advice));
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> failed("读取失败，请重新分析；原记录保留。"));
            }
        }, "schedule-advice").start();
    }

    private void render(List<Optimizer.Advice> items) {
        if (!active()) {
            return;
        }
        refresh.setEnabled(true);
        result.removeAllViews();
        if (items.isEmpty()) {
            text(result, "目前没有达到观察门槛的规律。继续记录即可，无需补造实际时间。");
        }
        for (int index = 0; index < Math.min(items.size(), 100); index++) {
            Optimizer.Advice advice = items.get(index);
            Suggestion suggestion = advice.suggestion;
            text(result, advice.title + "\n" + (suggestion.formal ? "达到建议门槛" : "趋势观察")
                    + " · " + suggestion.matching + " / " + suggestion.samples + " 个有效样本\n" + advice.reason);
            if (advice.adjustable) {
                text(result, values(suggestion) + "\n最早生效：" + suggestion.futureFrom
                        + "；保留原有单日例外，不改历史。");
                if (suggestion.formal) {
                    button(result, "预览未来调整", () -> preview(advice));
                }
            } else {
                text(result, "仅供观察，请结合实际情况判断；没有可直接应用的时间调整。");
            }
        }
        if (items.size() > 100) {
            text(result, "本页显示前 100 项。");
        }
    }

    private String values(Suggestion suggestion) {
        if ("START".equals(suggestion.kind)) {
            return "开始：" + clock(suggestion.oldValue) + " → " + clock(suggestion.proposedValue);
        }
        return "计划时长：" + suggestion.oldValue + " → " + suggestion.proposedValue + " 分钟";
    }

    private String clock(String value) {
        int minute = Integer.parseInt(value);
        return java.time.LocalTime.of(minute / 60, minute % 60).toString();
    }

    private void preview(Optimizer.Advice advice) {
        try (Store store = new Store(activity)) {
            Plan current = store.plans().stream().filter(p -> p.id.equals(advice.suggestion.planId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("计划不存在"));
            Plan draft = Optimizer.preview(current, advice, System.currentTimeMillis());
            LocalDate first = LocalDate.parse(advice.suggestion.futureFrom);
            List<Instance> future = Recurrence.generate(PlanEdits.withDefaults(draft, store.settings()),
                    first, first.plusDays(180));
            result.removeAllViews();
            text(result, advice.title + "\n" + advice.reason + "\n样本：" + advice.suggestion.evidence
                    + "\n" + values(advice.suggestion) + "\n自 " + first + " 起生效，保留既有单日例外。");
            showFuture(future, store.instances());
            Button confirm = button(result, "确认应用到未来日程", () -> accept(advice));
            confirm.setEnabled(!future.isEmpty());
            button(result, "取消并重新分析", this::load);
        } catch (RuntimeException error) {
            failed("无法预览：" + error.getMessage());
        }
    }

    private void showFuture(List<Instance> future, List<Instance> existing) {
        int conflicts = 0;
        for (Instance item : future) {
            for (Instance prior : existing) {
                if (!item.planId.equals(prior.planId) && Recurrence.overlaps(item, prior)) {
                    conflicts++;
                }
            }
        }
        text(result, "未来 180 天：" + future.size() + " 项，重叠 " + conflicts + " 处；请核对后确认。");
        for (int index = 0; index < Math.min(future.size(), 8); index++) {
            Instance item = future.get(index);
            String start = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(Instant.ofEpochMilli(item.start).atZone(ZoneId.of(item.zone)));
            text(result, start + " · " + (item.end - item.start) / 60000 + " 分钟");
        }
        if (future.isEmpty()) {
            text(result, "未来 180 天没有实例，此页不能应用。");
        }
    }

    private void accept(Optimizer.Advice advice) {
        try (Store store = new Store(activity)) {
            store.acceptAdvice(advice, System.currentTimeMillis());
        } catch (RuntimeException error) {
            failed("未应用：" + error.getMessage());
            return;
        }
        // Saving succeeded even if the OS temporarily refuses to reschedule alarms.
        ReminderWork.request(activity, false, success -> {
            if (!success && active()) {
                Toast.makeText(activity, "日程已保存；提醒将在下次调度时重试。", Toast.LENGTH_LONG).show();
            }
        });
        load();
    }

    private void failed(String message) {
        if (active()) {
            refresh.setEnabled(true);
            result.removeAllViews();
            text(result, message);
        }
    }

    private void text(LinearLayout parent, String value) {
        ScheduleUi.text(parent, value, 16);
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        return ScheduleUi.button(parent, label, action);
    }
}
