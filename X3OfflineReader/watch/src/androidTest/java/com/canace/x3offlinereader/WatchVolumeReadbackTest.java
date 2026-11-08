package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.widget.SeekBar;
import com.canace.x3offlinereader.ui.RoundVolumeDialog;

/** Tests clamping and focus recovery without writing system audio settings. */
public final class WatchVolumeReadbackTest extends InstrumentationTestCase {
    public void testClampedSliderAndFocusRecovery() {
        Activity activity = getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), BookLibraryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().runOnMainSync(() -> verifyDialog(activity));
    }

    private void verifyDialog(Activity activity) {
        Dialog[] dialog = new Dialog[1];
        FakeVolume fake = new FakeVolume();
        try {
            dialog[0] = RoundVolumeDialog.show(activity, 10, 4,
                    requested -> {
                        fake.writes++;
                        fake.actual = Math.min(requested, 5);
                        RoundVolumeDialog.syncActualVolume(dialog[0], 10, fake.actual);
                    }, fake);
            SeekBar seek = dialog[0].findViewById(R.id.volume_dialog_seek);
            seek.setProgress(8);
            assertEquals(5, seek.getProgress());
            assertEquals(1, fake.writes);
            dialog[0].onWindowFocusChanged(false);
            fake.actual = 3;
            dialog[0].onWindowFocusChanged(true);
            assertEquals(3, seek.getProgress());
            assertEquals(1, fake.writes);
        } finally {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            activity.finish();
        }
    }

    private static final class FakeVolume implements RoundVolumeDialog.CrownControl {
        private int actual = 4;
        private int writes;

        @Override
        public boolean onCrown(MotionEvent event) {
            return false;
        }

        @Override
        public int currentVolume() {
            return actual;
        }

        @Override
        public void reset() { }
    }
}
