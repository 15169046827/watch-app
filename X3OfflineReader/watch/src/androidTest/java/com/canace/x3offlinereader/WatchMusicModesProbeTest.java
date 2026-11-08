package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@SuppressWarnings("deprecation")
public final class WatchMusicModesProbeTest extends InstrumentationTestCase {

    private static final int TIMEOUT_MS = 10_000;

    private static final String MODE_SETTING = "playback_mode";

    private MediaController session;

    private List<MusicModels.Track> tracks;

    private int transitionReason;

    public void testMusicButtonsAndAutomaticPlaybackModes() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        try (MusicLibraryDb db = new MusicLibraryDb(context)) {
            assertTrue("Requires an empty acceptance music library", db.tracks().isEmpty());
            assertNull("Close the active player before starting the probe",
                    PlaybackTargetStore.activeDestination(context));
            ListenableFuture<MediaController> future = onMain(() -> new MediaController.Builder(
                    context, new SessionToken(context, new ComponentName(context, PlaybackService.class)))
                    .buildAsync());
            try {
                session = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                verifyWithFixtures(context, db);
            } finally {
                getInstrumentation().runOnMainSync(() -> MediaController.releaseFuture(future));
            }
        }
    }

    private void verifyWithFixtures(Context context, MusicLibraryDb db) throws Exception {
        assertEquals("Requires an empty MediaSession queue", 0,
                (int) onMain(session::getMediaItemCount));
        int repeat = onMain(session::getRepeatMode);
        boolean shuffle = onMain(session::getShuffleModeEnabled);
        float speed = onMain(() -> session.getPlaybackParameters().speed);
        String oldMode = db.settingValue(MODE_SETTING, null);
        List<File> files = new ArrayList<>();
        Player.Listener listener = new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                transitionReason = reason;
            }
        };
        getInstrumentation().runOnMainSync(() -> session.addListener(listener));
        try {
            addFixtures(context, db, files);
            tracks = db.tracks();
            assertEquals(3, tracks.size());
            inMode(context, db, "stop_after_track", Player.REPEAT_MODE_OFF, false, 1, activity -> {
                clickAndCheck(activity, R.id.previous_button, 0);
                clickAndCheck(activity, R.id.next_button, 1);
                reachEnd(1);
                await("pause after advancing to the next track", () ->
                        at(2) && !session.getPlayWhenReady()
                                && transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
            });
            inMode(context, db, "repeat_one", Player.REPEAT_MODE_ONE, false, 1,
                    activity -> verifyRepeatOne(1));
            inMode(context, db, "repeat_all", Player.REPEAT_MODE_ALL, false, 2, activity -> {
                reachEnd(2);
                expectPlaying(0, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
                reachEnd(0);
                expectPlaying(1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
            });
            inMode(context, db, "shuffle", Player.REPEAT_MODE_ALL, true, 0,
                    activity -> verifyShuffleCycle());
        } finally {
            getInstrumentation().runOnMainSync(() -> {
                session.removeListener(listener);
                session.setRepeatMode(repeat);
                session.setShuffleModeEnabled(shuffle);
                session.setPlaybackSpeed(speed);
            });
            restoreMode(db, oldMode);
            removeFixtures(db, files);
        }
    }

    private void inMode(Context context, MusicLibraryDb db, String mode, int repeat,
            boolean shuffle, int start, java.util.function.Consumer<Activity> checks) {
        db.putSetting(MODE_SETTING, mode);
        Activity activity = getInstrumentation().startActivitySync(
                new Intent(context, MusicPlayerActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        .putExtra("track_id", tracks.get(start).id));
        try {
            await(mode + " session ready", () -> at(start)
                    && session.getMediaItemCount() == tracks.size()
                    && session.getPlaybackState() == Player.STATE_READY
                    && session.getRepeatMode() == repeat && session.getShuffleModeEnabled() == shuffle
                    && activity.findViewById(R.id.next_button).isEnabled());
            checks.accept(activity);
        } finally {
            getInstrumentation().runOnMainSync(activity::finishAndRemoveTask);
            getInstrumentation().waitForIdleSync();
            await("fixture queue cleared", () -> session.getMediaItemCount() == 0);
        }
    }

    private void verifyShuffleCycle() {
        int index = 0;
        Set<Integer> visited = new HashSet<>();
        for (int step = 0; step < tracks.size(); step++) {
            assertTrue("Shuffle revisited an item before finishing the cycle", visited.add(index));
            int next = onMain(session::getNextMediaItemIndex);
            assertTrue("Shuffle must provide a valid successor", next >= 0 && next < tracks.size());
            reachEnd(index);
            expectPlaying(next, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
            index = next;
        }
        assertEquals("Shuffle must wrap to the starting item", 0, index);
    }

    private void verifyRepeatOne(int index) {
        reachEnd(index);
        // Observe playback near the end first, so a seek or initial position cannot count as a repeat.
        await("same fixture playing near its end", () -> at(index) && session.isPlaying()
                && session.getCurrentPosition() >= session.getDuration() - 700);
        await("same fixture playing again near its beginning", () -> at(index) && session.isPlaying()
                && session.getCurrentPosition() > 40
                && session.getCurrentPosition() < Math.min(750, session.getDuration() / 4));
    }

    private void clickAndCheck(Activity activity, int button, int index) {
        assertTrue(onMain(() -> activity.findViewById(button).performClick()));
        await("skip to fixture at index " + index, () -> at(index));
    }

    private void reachEnd(int index) {
        await("ready to play fixture at index " + index,
                () -> at(index) && session.getPlaybackState() == Player.STATE_READY
                        && session.getDuration() > 1_000);
        getInstrumentation().runOnMainSync(() -> {
            transitionReason = -1;
            session.seekTo(session.getDuration() - 850);
            session.play();
        });
    }

    private void expectPlaying(int index, int reason) {
        await("automatic transition with playback at index " + index,
                () -> at(index) && transitionReason == reason && session.isPlaying()
                        && session.getCurrentPosition() > 40 && session.getCurrentPosition() < 750);
    }

    private boolean at(int index) {
        MediaItem item = session.getCurrentMediaItem();
        return item != null && session.getCurrentMediaItemIndex() == index
                && ("music:" + tracks.get(index).id).equals(item.mediaId);
    }

    private void addFixtures(Context context, MusicLibraryDb db, List<File> files) throws Exception {
        for (int index = 0; index < 3; index++) {
            File file = File.createTempFile("music-modes-", ".mp3", context.getCacheDir());
            files.add(file);
            try (InputStream input = getInstrumentation().getContext().getAssets().open("device-smoke.mp3");
                    FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8_192];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, count);
                }
            }
            db.upsert(file.getAbsolutePath(), "Music modes fixture " + index, "", null, 0);
        }
    }

    private static void restoreMode(MusicLibraryDb db, String mode) {
        if (mode == null) {
            db.getWritableDatabase().delete("settings", "key=?", new String[] {MODE_SETTING});
        } else {
            db.putSetting(MODE_SETTING, mode);
        }
    }

    private static void removeFixtures(MusicLibraryDb db, List<File> files) {
        boolean removed = true;
        for (MusicModels.Track track : db.tracks()) {
            if (files.contains(new File(track.path))) {
                removed &= db.removeTrack(track.id, true);
            }
        }
        for (File file : files) {
            removed &= !file.exists() || file.delete();
        }
        assertTrue("Remove all fixture rows and audio files", removed);
    }

    private void await(String description, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        do {
            assertNull("Playback error while checking " + description, onMain(session::getPlayerError));
            if (onMain(condition::getAsBoolean)) {
                return;
            }
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + description);
    }

    private <T> T onMain(Supplier<T> query) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(query.get()));
        return value.get();
    }
}
