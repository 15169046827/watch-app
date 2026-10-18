package com.canace.x3schedule.shared;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/** Product-owned native components, with phone and watch palettes supplied by resources. */
final class ScheduleUi {
    private ScheduleUi() {
    }

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static int color(Context context, int resource) {
        return context.getColor(resource);
    }

    static boolean phone(Context context) {
        return context.getResources().getBoolean(R.bool.schedule_phone);
    }

    static LinearLayout.LayoutParams spacing(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 8);
        return params;
    }

    static GradientDrawable surface(Context context, int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(context, fill));
        shape.setCornerRadius(dp(context, 16));
        return shape;
    }

    static void text(TextView view, int size) {
        Context context = view.getContext();
        view.setTextSize(size);
        view.setTextColor(color(context, size <= 13 ? R.color.schedule_muted : R.color.schedule_text));
        view.setPadding(0, dp(context, 6), 0, dp(context, 6));
        view.setLineSpacing(dp(context, 2), 1);
    }

    static TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(parent.getContext());
        text(view, size);
        view.setText(value);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    static void heading(LinearLayout parent, String value) {
        Context context = parent.getContext();
        TextView view = text(parent, value, phone(context) ? 18 : 16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(context, 18), 0, dp(context, 8));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
    }

    static void button(Button view) {
        Context context = view.getContext();
        view.setAllCaps(false);
        view.setTextSize(phone(context) ? 15 : 14);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 48));
        view.setMinimumHeight(dp(context, 48));
        view.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        view.setStateListAnimator(null);
        view.setElevation(0);
        view.setLayoutParams(spacing(context));
        buttonColors(view, R.color.schedule_card, R.color.schedule_text);
    }

    static void primary(Button view) {
        buttonColors(view, R.color.schedule_accent, R.color.schedule_on_accent);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    private static void buttonColors(Button view, int fill, int foreground) {
        Context context = view.getContext();
        int[][] states = { { -android.R.attr.state_enabled }, { android.R.attr.state_focused }, { } };
        view.setTextColor(new ColorStateList(states, new int[] {
            color(context, R.color.schedule_muted), color(context, foreground), color(context, foreground)
        }));
        android.graphics.drawable.StateListDrawable backgrounds = new android.graphics.drawable.StateListDrawable();
        backgrounds.addState(new int[] { -android.R.attr.state_enabled }, surface(context, R.color.schedule_subtle));
        GradientDrawable focused = surface(context, fill);
        focused.setStroke(dp(context, 2), color(context, foreground));
        backgrounds.addState(new int[] { android.R.attr.state_focused }, focused);
        backgrounds.addState(new int[0], surface(context, fill));
        view.setBackgroundTintList(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(color(context, R.color.schedule_ripple)),
                backgrounds, null));
        // Some framework versions retain the child drawable's initial disabled state until a state change.
        view.getBackground().setState(new int[0]);
        view.getBackground().setState(view.getDrawableState());
    }

    static Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(parent.getContext());
        button(view);
        view.setText(label);
        view.setOnClickListener(ignored -> action.run());
        parent.addView(view);
        return view;
    }

    static void field(EditText view) {
        Context context = view.getContext();
        text(view, 16);
        view.setTextColor(new ColorStateList(new int[][] { { -android.R.attr.state_enabled }, { } },
                new int[] { color(context, R.color.schedule_muted), color(context, R.color.schedule_text) }));
        view.setHintTextColor(color(context, R.color.schedule_muted));
        view.setMinHeight(dp(context, 48));
        view.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        GradientDrawable background = surface(context, R.color.schedule_card);
        background.setStroke(dp(context, 1), color(context, R.color.schedule_border));
        view.setBackground(background);
        view.setBackgroundTintList(null);
        view.setLayoutParams(spacing(context));
    }

    static void choice(Spinner view) {
        Context context = view.getContext();
        view.setMinimumHeight(dp(context, 48));
        view.setBackgroundTintList(ColorStateList.valueOf(color(context, R.color.schedule_accent)));
        view.setPopupBackgroundDrawable(surface(context, R.color.schedule_card));
        view.setLayoutParams(spacing(context));
    }

    static TextView status(LinearLayout parent, String value) {
        Context context = parent.getContext();
        TextView view = text(parent, value, 13);
        view.setTextColor(color(context, R.color.schedule_accent));
        view.setBackground(surface(context, R.color.schedule_subtle));
        view.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        view.setLayoutParams(spacing(context));
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }
}
