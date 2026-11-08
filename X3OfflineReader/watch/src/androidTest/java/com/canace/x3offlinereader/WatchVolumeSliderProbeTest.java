package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.media.AudioManager;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import com.canace.x3offlinereader.ui.RoundVolumeDialog;
import java.util.concurrent.atomic.AtomicInteger;

/** Self-contained continuous-volume dialog and round-layout probe. */
public final class WatchVolumeSliderProbeTest extends InstrumentationTestCase {

    public void testSliderChangesSystemMediaVolumeAndFitsRoundScreen() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int maximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int original = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = Math.min(maximum, Math.max(1, maximum / 2));
        Activity activity = null;
        Dialog[] holder = new Dialog[1];
        AtomicInteger changed = new AtomicInteger(-1);
        try {
            activity =
                    getInstrumentation()
                            .startActivitySync(
                                    new android.content.Intent(context, BookLibraryActivity.class)
                                            .addFlags(
                                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity owner = activity;
            getInstrumentation()
                    .runOnMainSync(
                            () ->
                                    holder[0] =
                                            RoundVolumeDialog.show(
                                                    owner,
                                                    maximum,
                                                    original,
                                                    value -> {
                                                        changed.set(value);
                                                        audio.setStreamVolume(
                                                                AudioManager.STREAM_MUSIC,
                                                                value,
                                                                0);
                                                    }));
            getInstrumentation().waitForIdleSync();
            SeekBar seek = holder[0].findViewById(R.id.volume_dialog_seek);
            ImageButton decrease = holder[0].findViewById(R.id.volume_dialog_decrease);
            ImageButton increase = holder[0].findViewById(R.id.volume_dialog_increase);
            ImageButton close = holder[0].findViewById(R.id.volume_dialog_close);
            Switch crown = holder[0].findViewById(R.id.volume_dialog_crown_enabled);
            assertNotNull(crown);
            assertInsideDisplay(crown);
            assertNotNull(seek);
            assertNotNull(decrease);
            assertNotNull(increase);
            assertNotNull(close);
            assertInsideDisplay(seek);
            assertInsideDisplay(decrease);
            assertInsideDisplay(increase);
            assertInsideDisplay(close);
            assertTrue(seek.getWidth() >= 180);
            assertNoTextViews(holder[0].getWindow().getDecorView());
            getInstrumentation().runOnMainSync(() -> seek.setProgress(target));
            getInstrumentation().waitForIdleSync();
            assertEquals(target, changed.get());
            assertEquals(target, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            int lower = Math.max(0, target - 1);
            getInstrumentation().runOnMainSync(decrease::performClick);
            getInstrumentation().waitForIdleSync();
            assertEquals(lower, changed.get());
            getInstrumentation().runOnMainSync(increase::performClick);
            getInstrumentation().waitForIdleSync();
            assertEquals(Math.min(maximum, lower + 1), changed.get());
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0);
            Activity closing = activity;
            getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                if (holder[0] != null) {
                                    holder[0].dismiss();
                                }
                                if (closing != null) {
                                    closing.finish();
                                }
                            });
            getInstrumentation().waitForIdleSync();
        }
    }

    private static void assertInsideDisplay(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        assertTrue(view.getVisibility() == View.VISIBLE);
        assertTrue(at[0] >= 0 && at[1] >= 0);
        assertTrue(at[0] + view.getWidth() <= 466);
        assertTrue(at[1] + view.getHeight() <= 466);
    }

    private static void assertNoTextViews(View view) {
        assertTrue(!(view instanceof TextView) || view instanceof Switch);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertNoTextViews(group.getChildAt(i));
            }
        }
    }
}
