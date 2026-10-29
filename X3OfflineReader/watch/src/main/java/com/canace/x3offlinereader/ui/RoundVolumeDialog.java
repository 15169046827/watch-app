package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.playback.CrownMotionEvents;
import com.canace.x3offlinereader.playback.CrownVolumePreferences;

/** Full-screen round-safe drag control for the system media-volume steps. */
@SuppressWarnings("deprecation")
public final class RoundVolumeDialog {

    public interface Listener {

        void onVolume(int value);
    }

    public interface CrownControl {

        boolean onCrown(MotionEvent event);

        int currentVolume();

        void reset();
    }

    private RoundVolumeDialog() { }

    public static Dialog show(Activity activity, int maximum, int current, Listener listener) {
        return show(activity, maximum, current, listener, null);
    }

    public static Dialog show(
            Activity activity, int maximum, int current, Listener listener, CrownControl crown) {
        int max = Math.max(1, maximum);
        int initial = Math.max(0, Math.min(max, current));
        VolumeDialog dialog = new VolumeDialog(activity, crown);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(activity.getColor(R.color.screen_background));
        addCrownSwitch(activity, root, crown);
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        ImageButton decrease =
                iconButton(
                        activity,
                        R.id.volume_dialog_decrease,
                        R.drawable.ic_volume_remove,
                        R.string.volume_lower);
        ImageButton increase =
                iconButton(
                        activity,
                        R.id.volume_dialog_increase,
                        R.drawable.ic_volume_add,
                        R.string.volume_raise);
        SeekBar seek = new SeekBar(activity);
        seek.setId(R.id.volume_dialog_seek);
        seek.setMax(max);
        seek.setProgress(initial);
        seek.setContentDescription(activity.getString(R.string.volume_drag_hint));
        seek.setSplitTrack(false);
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
        controlsParams.topMargin = dp(activity, 8);
        root.addView(controls, controlsParams);
        ImageButton close =
                iconButton(
                        activity,
                        R.id.volume_dialog_close,
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
        decrease.setOnClickListener(v -> seek.setProgress(Math.max(0, seek.getProgress() - 1)));
        increase.setOnClickListener(v -> seek.setProgress(Math.min(max, seek.getProgress() + 1)));
        close.setOnClickListener(v -> dialog.dismiss());
        seek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (!dialog.syncing) {
                            listener.onVolume(progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        if (crown != null) {
                            crown.reset();
                        }
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) { }
                });
        dialog.bind(seek);
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

    private static void addCrownSwitch(Activity activity, FrameLayout root, CrownControl crown) {
        Switch toggle = new Switch(activity);
        toggle.setId(R.id.volume_dialog_crown_enabled);
        toggle.setText(R.string.volume_crown_enabled);
        toggle.setTextSize(14);
        toggle.setTextColor(activity.getColor(R.color.text_primary));
        toggle.setChecked(CrownVolumePreferences.isEnabled(activity));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            CrownVolumePreferences.setEnabled(activity, enabled);
            if (crown != null) {
                crown.reset();
            }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(activity, 172), dp(activity, 48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(activity, 44);
        root.addView(toggle, params);
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
        return button;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class VolumeDialog extends Dialog {

        private final CrownControl crown;

        private SeekBar seek;

        private boolean syncing;

        VolumeDialog(Activity activity, CrownControl crown) {
            super(activity);
            this.crown = crown;
        }

        void bind(SeekBar seek) {
            this.seek = seek;
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (CrownMotionEvents.delta(event) != 0f
                    && !CrownVolumePreferences.isEnabled(getContext())) {
                if (crown != null) {
                    crown.reset();
                }
                return true;
            }
            if (crown != null && crown.onCrown(event)) {
                syncing = true;
                seek.setProgress(crown.currentVolume());
                syncing = false;
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus && crown != null) {
                crown.reset();
            }
            super.onWindowFocusChanged(hasFocus);
        }

        @Override
        protected void onStop() {
            if (crown != null) {
                crown.reset();
            }
            super.onStop();
        }
    }
}
