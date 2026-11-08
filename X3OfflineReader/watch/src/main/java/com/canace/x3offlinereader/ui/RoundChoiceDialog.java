package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.canace.x3offlinereader.R;

/** Opaque fixed-header choice dialog for the clipped, round OPPO watch window. */
public final class RoundChoiceDialog {
    public interface Listener {
        void onChoice(int which);
    }

    private RoundChoiceDialog() { }

    public static Dialog show(
            Activity activity,
            CharSequence title,
            String[] labels,
            int checked,
            Listener listener) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 8);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(activity.getColor(R.color.dialog_background));
        background.setCornerRadius(dp(activity, 18));
        root.setBackground(background);
        TextView header = new TextView(activity);
        header.setId(R.id.choice_dialog_title);
        header.setText(title);
        header.setTextColor(activity.getColor(R.color.text_primary));
        header.setTextSize(17);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        header.setMaxLines(2);
        header.setMinHeight(dp(activity, 34));
        header.setPadding(dp(activity, 20), 0, dp(activity, 20), 0);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setBackgroundColor(activity.getColor(R.color.dialog_background));
        root.addView(
                header,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View divider = new View(activity);
        divider.setBackgroundColor(activity.getColor(R.color.divider));
        root.addView(
                divider,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1)));
        ListView list = new ListView(activity);
        list.setId(R.id.choice_dialog_list);
        if (checked >= 0) {
            list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        }
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(activity, 3));
        list.setDrawSelectorOnTop(true);
        list.setSelector(R.drawable.watch_book_row_feedback);
        list.setAdapter(
                new ArrayAdapter<>(
                        activity, checked >= 0 ? R.layout.item_choice_entry : R.layout.item_library_entry,
                        android.R.id.text1, labels));
        if (checked >= 0 && checked < labels.length) {
            list.setItemChecked(checked, true);
        }
        root.addView(
                list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Button cancel = new Button(activity);
        cancel.setText(android.R.string.cancel);
        cancel.setBackgroundResource(R.drawable.watch_action_pill);
        cancel.setStateListAnimator(null);
        cancel.setMinHeight(0);
        cancel.setPadding(0, 0, 0, 0);
        cancel.setTextSize(11);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelBounds = new LinearLayout.LayoutParams(
                dp(activity, 110), dp(activity, 30));
        cancelBounds.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(cancel, cancelBounds);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    dialog.dismiss();
                    listener.onChoice(position);
                });
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 48),
                    activity.getResources().getDisplayMetrics().heightPixels - dp(activity, 36));
            window.setGravity(Gravity.CENTER);
        }
        if (checked >= 0 && checked < labels.length) {
            list.post(
                    () -> {
                        list.setItemChecked(checked, true);
                        list.setSelection(checked);
                    });
        }
        return dialog;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
