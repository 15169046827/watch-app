package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.music.MusicScanner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Verifies that a transient competing audio-focus owner pauses and then releases playback. */
public final class WatchAudioFocusProbeTest extends InstrumentationTestCase {
    public void testTransientAudioFocusLossPausesAndResumes() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        MusicLibraryDb db = new MusicLibraryDb(target);
        Activity player = null;
        AudioManager audio = null;
        AudioManager.OnAudioFocusChangeListener competitor = change -> { };
        String oldSpeed = db.settingValue("playback_speed", "1.0");
        try {
            File file = new File(MusicScanner.musicDir(target), "device-background.m4a");
            copyAsset("device-background.m4a", file);
            assertTrue(MusicScanner.scan(target, db) > 0);
            MusicModels.Track track = null;
            for (MusicModels.Track item : db.tracks()) {
                if (item.path.endsWith("device-background.m4a")) {
                    track = item;
                }
            }
            assertNotNull(track);
            final MusicModels.Track selected = track;
            db.putSetting("playback_speed", "1.0");
            db.saveProgress(selected.id, 0);
            Intent intent =
                    new Intent(target, MusicPlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("track_id", selected.id);
            player = getInstrumentation().startActivitySync(intent);
            final Activity active = player;
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active).contains(" / "),
                    20000);
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            waitFor(() -> position(active) >= 1000, 10000);
            audio =
                    (AudioManager)
                            getInstrumentation()
                                    .getContext()
                                    .getSystemService(Context.AUDIO_SERVICE);
            assertNotNull(audio);
            assertEquals(
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audio.requestAudioFocus(
                            competitor,
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT));
            long pausedAt = position(active);
            Thread.sleep(1600);
            long heldAt = position(active);
            assertTrue(
                    "playback advanced while focus was held: " + pausedAt + " -> " + heldAt,
                    heldAt - pausedAt < 600);
            assertEquals(
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.abandonAudioFocus(competitor));
            waitFor(() -> position(active) >= heldAt + 800, 6000);
        } finally {
            if (audio != null) {
                audio.abandonAudioFocus(competitor);
            }
            if (player != null) {
                Activity closing = player;
                getInstrumentation().runOnMainSync(closing::finish);
                getInstrumentation().waitForIdleSync();
            }
            db.putSetting("playback_speed", oldSpeed);
            File fixture = new File(MusicScanner.musicDir(target), "device-background.m4a");
            if (fixture.isFile()) {
                assertTrue(fixture.delete());
            }
            MusicScanner.scan(target, db);
            db.close();
        }
    }

    private void copyAsset(String name, File target) throws Exception {
        try (InputStream in = getInstrumentation().getContext().getAssets().open(name);
                FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (n > 0) {
                    out.write(buffer, 0, n);
                }
            }
        }
    }

    private static String text(Activity activity) {
        return ((TextView) activity.findViewById(R.id.position)).getText().toString();
    }

    private static long position(Activity activity) {
        String text = text(activity);
        int colon = text.indexOf(':');
        int space = text.indexOf(' ');
        if (colon < 0 || space < 0) {
            return 0;
        }
        return (Long.parseLong(text.substring(0, colon)) * 60
                        + Long.parseLong(text.substring(colon + 1, space)))
                * 1000;
    }

    private void waitFor(Check check, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            getInstrumentation().waitForIdleSync();
            if (check.ok()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for playback state");
    }

    private interface Check {
        boolean ok() throws Exception;
    }
}
