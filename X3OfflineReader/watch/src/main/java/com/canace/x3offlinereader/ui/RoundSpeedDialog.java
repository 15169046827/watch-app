package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.playback.PlaybackSpeedRange;

/** Full-screen round-safe 0.5x-5.0x playback-speed slider. */
public final class RoundSpeedDialog {
    public interface Listener {
        void onSpeed(float speed);
    }

    private RoundSpeedDialog() { }

    public static Dialog show(Activity activity, float current, Listener listener) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(activity.getColor(R.color.screen_background));
        TextView title = new TextView(activity);
        title.setText(R.string.speed_title);
        title.setTextColor(activity.getColor(R.color.text_primary));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams =
                new FrameLayout.LayoutParams(
                        dp(activity, 190),
                        dp(activity, 34),
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleParams.topMargin = dp(activity, 34);
        root.addView(title, titleParams);
        TextView value = new TextView(activity);
        value.setId(R.id.speed_dialog_value);
        value.setTextColor(activity.getColor(R.color.accent));
        value.setTextSize(20);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams valueParams =
                new FrameLayout.LayoutParams(
                        dp(activity, 150),
                        dp(activity, 40),
                        Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        valueParams.topMargin = dp(activity, 78);
        root.addView(value, valueParams);
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        ImageButton decrease =
                iconButton(
                        activity,
                        R.id.speed_dialog_decrease,
                        R.drawable.ic_volume_remove,
                        R.string.speed_lower);
        ImageButton increase =
                iconButton(
                        activity,
                        R.id.speed_dialog_increase,
                        R.drawable.ic_volume_add,
                        R.string.speed_raise);
        SeekBar seek = new SeekBar(activity);
        seek.setId(R.id.speed_dialog_seek);
        seek.setMax(PlaybackSpeedRange.MAX_PROGRESS);
        seek.setProgress(PlaybackSpeedRange.progress(current));
        seek.setKeyProgressIncrement(1);
        seek.setSplitTrack(false);
        seek.setContentDescription(activity.getString(R.string.speed_drag_hint));
        seek.setProgressTintList(ColorStateList.valueOf(activity.getColor(R.color.accent)));
        seek.setProgressBackgroundTintList(
                ColorStateList.valueOf(activity.getColor(R.color.divider)));
        seek.setThumbTintList(ColorStateList.valueOf(activity.getColor(R.color.accent)));
        controls.addView(
                decrease, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 52)));
        controls.addView(seek, new LinearLayout.LayoutParams(0, dp(activity, 52), 1f));
        controls.addView(
                increase, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 52)));
        FrameLayout.LayoutParams controlsParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 64), Gravity.CENTER);
        controlsParams.leftMargin = dp(activity, 8);
        controlsParams.rightMargin = dp(activity, 8);
        root.addView(controls, controlsParams);
        ImageButton close =
                iconButton(
                        activity,
                        R.id.speed_dialog_close,
                        R.drawable.ic_volume_close,
                        R.string.close);
        close.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.text_secondary)));
        FrameLayout.LayoutParams closeParams =
                new FrameLayout.LayoutParams(
                        dp(activity, 48),
                        dp(activity, 48),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        closeParams.bottomMargin = dp(activity, 8);
        root.addView(close, closeParams);
        SeekBar.OnSeekBarChangeListener changes =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        float speed = PlaybackSpeedRange.speed(progress);
                        value.setText(PlaybackSpeedRange.label(speed));
                        listener.onSpeed(speed);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) { }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) { }
                };
        seek.setOnSeekBarChangeListener(changes);
        value.setText(PlaybackSpeedRange.label(current));
        decrease.setOnClickListener(v -> seek.setProgress(Math.max(0, seek.getProgress() - 1)));
        increase.setOnClickListener(
                v ->
                        seek.setProgress(
                                Math.min(PlaybackSpeedRange.MAX_PROGRESS, seek.getProgress() + 1)));
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
        }
        return dialog;
    }

    private static ImageButton iconButton(
            Activity activity, int id, int drawable, int description) {
        ImageButton button = new ImageButton(activity);
        button.setId(id);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.text_primary)));
        button.setContentDescription(activity.getString(description));
        button.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        button.setStateListAnimator(null);
        return button;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
