package com.canace.x3schedule.shared;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Progress;
import com.canace.x3schedule.domain.ProgressReport;
import java.util.Map;

/** Actual achievement colours never imply the weighted execution score. Text remains essential. */
final class ProgressAppearance {
    private ProgressAppearance() {
    }

    private static int gradeColor(Context context, Progress.Grade grade) {
        int[] resources = { R.color.schedule_result_none, R.color.schedule_result_minimum,
            R.color.schedule_result_basic, R.color.schedule_result_perfect };
        return ScheduleUi.color(context, resources[grade.ordinal()]);
    }

    static void grade(TextView view, Progress.Grade grade, boolean exempt) {
        boolean watch = !ScheduleUi.phone(view.getContext());
        int color = exempt || grade == null ? ScheduleUi.color(view.getContext(), R.color.schedule_result_exempt)
                : gradeColor(view.getContext(), grade);
        int base = ScheduleUi.color(view.getContext(), R.color.schedule_card);
        int blend = blend(base, color, watch ? 0.16 : 0.08);
        GradientDrawable card = new GradientDrawable();
        card.setColor(blend);
        card.setCornerRadius(ScheduleUi.dp(view.getContext(), 14));
        card.setStroke(ScheduleUi.dp(view.getContext(), 2), color);
        view.setBackgroundTintList(null);
        view.setBackground(card);
        view.setTextColor(ScheduleUi.color(view.getContext(), R.color.schedule_text));
    }

    static void choice(TextView view, Progress.Grade grade) {
        grade(view, grade, false);
        GradientDrawable marker = new GradientDrawable();
        marker.setShape(GradientDrawable.OVAL);
        marker.setColor(gradeColor(view.getContext(), grade));
        int size = ScheduleUi.dp(view.getContext(), 12);
        marker.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(marker, null, null, null);
        view.setCompoundDrawablePadding(ScheduleUi.dp(view.getContext(), 8));
    }

    private static int blend(int base, int tint, double alpha) {
        return Color.rgb((int) (Color.red(base) * (1 - alpha) + Color.red(tint) * alpha),
                (int) (Color.green(base) * (1 - alpha) + Color.green(tint) * alpha),
                (int) (Color.blue(base) * (1 - alpha) + Color.blue(tint) * alpha));
    }

    static void item(TextView view, Instance item, Map<String, Progress.Summary> states) {
        Progress.Summary state = states.get(item.id);
        if (state == null || item.start > System.currentTimeMillis()) {
            return;
        }
        grade(view, state.grade, state.exempt());
        view.setText(view.getContext().getString(R.string.schedule_progress_summary,
                view.getText(), Progress.describe(state)));
        view.setContentDescription(view.getText());
    }

    static void score(TextView view, Double score) {
        int band = ProgressReport.band(score);
        int[] resources = { R.color.schedule_result_none, R.color.schedule_score_low,
            R.color.schedule_score_minimum, R.color.schedule_result_basic, R.color.schedule_result_perfect };
        Context context = view.getContext();
        int color = band < 0 ? ScheduleUi.color(context, R.color.schedule_muted)
                : ScheduleUi.color(context, resources[band]);
        view.setTextColor(ColorStateList.valueOf(color));
    }
}
