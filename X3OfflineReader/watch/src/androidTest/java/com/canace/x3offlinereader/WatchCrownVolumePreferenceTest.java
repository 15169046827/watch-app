package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import com.canace.x3offlinereader.playback.AudioOutputControls;
import com.canace.x3offlinereader.playback.CrownVolumePreferences;
import com.canace.x3offlinereader.ui.RoundVolumeDialog;

/** Product regression: shared opt-out suppresses crown volume, not touch controls. */
public final class WatchCrownVolumePreferenceTest extends InstrumentationTestCase {
    public void testOptOutPersistsAndBlocksPlayerAndDialogCrown() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean originalEnabled = CrownVolumePreferences.isEnabled(context);
        Activity activity = getInstrumentation().startActivitySync(
                new Intent(context, BookLibraryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            getInstrumentation().runOnMainSync(() -> verifyControls(activity, audio));
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
            CrownVolumePreferences.setEnabled(context, originalEnabled);
            getInstrumentation().runOnMainSync(activity::finish);
            getInstrumentation().waitForIdleSync();
        }
    }

    private void verifyControls(Activity activity, AudioManager audio) {
        CrownVolumePreferences.clear(activity);
        assertTrue(CrownVolumePreferences.isEnabled(activity));
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        AudioOutputControls controls = new AudioOutputControls(
                activity, new ImageButton(activity), new ImageButton(activity));
        Dialog dialog = showDialog(activity, audio, max);
        MotionEvent event = crownEvent();
        try {
            Switch toggle = dialog.findViewById(R.id.volume_dialog_crown_enabled);
            assertTrue(toggle.isChecked());
            toggle.performClick();
            assertFalse(CrownVolumePreferences.isEnabled(activity.getApplicationContext()));
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            assertTrue(controls.handleCrown(event));
            assertTrue(dialog.dispatchGenericMotionEvent(event));
            assertEquals(current, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            SeekBar seek = dialog.findViewById(R.id.volume_dialog_seek);
            seek.setProgress(0);
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            dialog.dismiss();
            dialog = showDialog(activity, audio, max);
            toggle = dialog.findViewById(R.id.volume_dialog_crown_enabled);
            assertFalse(toggle.isChecked());
            toggle.performClick();
            assertTrue(controls.handleCrown(event));
            assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally {
            event.recycle();
            dialog.dismiss();
        }
    }

    private Dialog showDialog(Activity activity, AudioManager audio, int maximum) {
        return RoundVolumeDialog.show(activity, maximum,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                value -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0));
    }

    private MotionEvent crownEvent() {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_SCROLL, -2f);
        return MotionEvent.obtain(0, 200, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[] {properties},
                new MotionEvent.PointerCoords[] {coords}, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_ROTARY_ENCODER, 0);
    }
}
