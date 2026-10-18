package com.canace.x3schedule.shared;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Phone dock and safe window insets; watch keeps a single vertically scrolling round-screen surface. */
final class ScheduleShell {
    final LinearLayout content;
    final ScrollView scroll;

    ScheduleShell(Activity activity, String title, Runnable back, int tab, IntConsumer select) {
        boolean phone = ScheduleUi.phone(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ScheduleUi.color(activity, R.color.schedule_background));
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = ScheduleUi.dp(activity, phone ? 20 : 36);
        content.setPadding(side, ScheduleUi.dp(activity, phone ? 16 : 38), side,
                ScheduleUi.dp(activity, phone ? 24 : 44));
        FrameLayout centered = new FrameLayout(activity);
        centered.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        centerContent(centered, phone ? 640 : 400);
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (phone) {
            if (select != null) {
                dock(root, tab, select);
            }
            insets(activity, root);
        }
        title(title, phone);
        if (back != null) {
            ScheduleUi.button(content, "‹ 返回", back);
        }
        activity.setContentView(root);
    }

    private void title(String title, boolean phone) {
        TextView heading = ScheduleUi.text(content, title, phone ? 26 : 21);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (!phone) {
            heading.setGravity(Gravity.CENTER);
        }
    }

    private void centerContent(FrameLayout container, int maxDp) {
        container.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, ScheduleUi.dp(container.getContext(), maxDp));
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
            if (params.width != width) {
                params.width = width;
                content.setLayoutParams(params);
            }
        });
    }

    private void dock(LinearLayout root, int selected, IntConsumer select) {
        LinearLayout dock = new LinearLayout(root.getContext());
        dock.setPadding(ScheduleUi.dp(root.getContext(), 12), ScheduleUi.dp(root.getContext(), 6),
                ScheduleUi.dp(root.getContext(), 12), 0);
        dock.setBackgroundColor(ScheduleUi.color(root.getContext(), R.color.schedule_card));
        String[] labels = { "日历", "日程", "报告", "设置" };
        for (int index = 0; index < labels.length; index++) {
            final int target = index;
            Button button = ScheduleUi.button(dock, labels[index], () -> select.accept(target));
            button.setSelected(index == selected);
            button.setContentDescription(labels[index] + (index == selected ? "，已选中" : ""));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.bottomMargin = ScheduleUi.dp(root.getContext(), 6);
            button.setLayoutParams(params);
            if (index == selected) {
                ScheduleUi.primary(button);
            }
        }
        root.addView(dock);
    }

    private void insets(Activity activity, View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
