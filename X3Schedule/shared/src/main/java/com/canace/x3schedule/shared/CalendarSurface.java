package com.canace.x3schedule.shared;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/** Activity-owned calendar surface retains its rendered children while the next projection loads. */
final class CalendarSurface {
    final LinearLayout body;
    private final FrameLayout frame;
    private final FrameLayout mask;
    private final ProgressBar progress;
    private final Runnable reveal;

    CalendarSurface(Context context) {
        frame = new FrameLayout(context);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        frame.addView(body, new FrameLayout.LayoutParams(-1, -2));
        mask = new FrameLayout(context);
        mask.setClickable(true);
        progress = new ProgressBar(context);
        progress.setContentDescription("正在刷新日历");
        int size = ScheduleUi.dp(context, 40);
        mask.addView(progress, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        frame.addView(mask, new FrameLayout.LayoutParams(-1, -1));
        reveal = () -> {
            int background = ScheduleUi.color(context, R.color.schedule_background);
            mask.setBackgroundColor((background & 0x00ffffff) | 0x99000000);
            positionProgress();
            progress.setVisibility(View.VISIBLE);
        };
        finish();
    }

    void attach(LinearLayout parent) {
        if (frame.getParent() instanceof ViewGroup) {
            ((ViewGroup) frame.getParent()).removeView(frame);
        }
        parent.addView(frame, new LinearLayout.LayoutParams(-1, -2));
    }

    void begin() {
        mask.removeCallbacks(reveal);
        mask.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        progress.setVisibility(View.GONE);
        mask.setVisibility(View.VISIBLE);
        body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (body.getChildCount() == 0) {
            body.setMinimumHeight(ScheduleUi.dp(body.getContext(), 400));
        }
        mask.postDelayed(reveal, 150);
    }

    void finish() {
        mask.removeCallbacks(reveal);
        mask.setVisibility(View.GONE);
        body.setMinimumHeight(0);
        body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    boolean loading() {
        return mask.getVisibility() == View.VISIBLE;
    }

    private void positionProgress() {
        Rect visible = new Rect();
        if (frame.getLocalVisibleRect(visible)) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progress.getLayoutParams();
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = Math.max(0, visible.centerY() - params.height / 2);
            progress.setLayoutParams(params);
        }
    }
}
