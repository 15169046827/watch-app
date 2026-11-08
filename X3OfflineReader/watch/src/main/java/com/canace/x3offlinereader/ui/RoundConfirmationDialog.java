package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import com.canace.x3offlinereader.R;

/** Compact round-screen prompt with fixed actions and a scrollable explanation. */
public final class RoundConfirmationDialog {
    private RoundConfirmationDialog() { }

    public static void confirm(Activity activity, CharSequence title, CharSequence message,
            int actionLabel, boolean destructive, Runnable action) {
        show(activity, title, message, actionLabel, destructive, true, action);
    }

    public static void notice(Activity activity, CharSequence title, CharSequence message,
            int actionLabel, Runnable action) {
        show(activity, title, message, actionLabel, false, false, action);
    }

    private static void show(Activity activity, CharSequence title, CharSequence message,
            int actionLabel, boolean destructive, boolean cancelable, Runnable action) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_round_confirmation);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        View root = dialog.findViewById(R.id.confirmation_panel);
        ScrollView scroll = dialog.findViewById(R.id.confirmation_scroll);
        TextView heading = dialog.findViewById(R.id.confirmation_title);
        TextView detail = dialog.findViewById(R.id.confirmation_message);
        heading.setText(title);
        detail.setText(message);
        detail.setVisibility(message.length() == 0 ? View.GONE : View.VISIBLE);
        Button cancel = dialog.findViewById(R.id.confirmation_cancel);
        cancel.setVisibility(cancelable ? View.VISIBLE : View.GONE);
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button confirm = dialog.findViewById(R.id.confirmation_action);
        confirm.setText(actionLabel);
        confirm.setBackgroundResource(destructive
                ? R.drawable.watch_confirmation_danger : R.drawable.watch_novel_tile);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });

        int width = Math.min(dp(activity, 166),
                activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32));
        int innerWidth = width - root.getPaddingLeft() - root.getPaddingRight();
        View content = scroll.getChildAt(0);
        content.measure(View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ViewGroup.LayoutParams bounds = scroll.getLayoutParams();
        bounds.height = Math.min(content.getMeasuredHeight(), dp(activity, 88));
        scroll.setLayoutParams(bounds);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
