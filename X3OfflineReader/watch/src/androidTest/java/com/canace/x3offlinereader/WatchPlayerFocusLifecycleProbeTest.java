package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.music.MusicLibraryActivity;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.music.MusicScanner;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Home/background keeps the player alive; launcher and MediaSession entries return to that page.
 */
public final class WatchPlayerFocusLifecycleProbeTest extends InstrumentationTestCase {

    public void testDesktopAndMediaShortcutsReturnToPlayerUntilRightSwipeStops() throws Exception {
        verifyShortcut(false);
        verifyShortcut(true);
    }

    private void verifyShortcut(boolean fromMedia) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Application application = (Application) context.getApplicationContext();
        ObservedActivities observed = new ObservedActivities();
        onMain(() -> {
            application.registerActivityLifecycleCallbacks(observed);
            return null;
        });
        MusicLibraryDb db = new MusicLibraryDb(context);
        ListenableFuture<MediaController> future = null;
        MediaController observer = null;
        String oldSpeed = db.settingValue("playback_speed", "1.0");
        String oldMode = db.settingValue("playback_mode", "stop_after_track");
        try {
            File file = new File(MusicScanner.musicDir(context), "device-background.m4a");
            copyAsset("device-background.m4a", file);
            assertTrue(MusicScanner.scan(context, db) > 0);
            MusicModels.Track selected = null;
            for (MusicModels.Track track : db.tracks()) {
                if (track.path.endsWith("device-background.m4a")) {
                    selected = track;
                }
            }
            assertNotNull(selected);
            db.putSetting("playback_speed", "1.0");
            db.putSetting("playback_mode", "repeat_one");
            db.saveProgress(selected.id, 0);
            Intent intent =
                    new Intent(context, MusicPlayerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                            .putExtra("track_id", selected.id);
            Activity active = getInstrumentation().startActivitySync(intent);
            waitFor(
                    () ->
                            active.findViewById(R.id.play_button).isEnabled()
                                    && text(active, R.id.position).contains(" / "),
                    20000);
            AtomicReference<ListenableFuture<MediaController>> pending = new AtomicReference<>();
            getInstrumentation()
                    .runOnMainSync(
                            () ->
                                    pending.set(
                                            new MediaController.Builder(
                                                            context,
                                                            new SessionToken(
                                                                    context,
                                                                    new ComponentName(
                                                                            context,
                                                                            PlaybackService.class)))
                                                    .buildAsync()));
            future = pending.get();
            observer = future.get(10, TimeUnit.SECONDS);
            final MediaController session = observer;
            getInstrumentation()
                    .runOnMainSync(() -> active.findViewById(R.id.play_button).performClick());
            waitFor(() -> onMain(session::isPlaying), 8000);
            PendingIntent shortcut = onMain(session::getSessionActivity);
            assertNotNull(shortcut);
            getInstrumentation().runOnMainSync(() -> active.moveTaskToBack(true));
            Thread.sleep(1500);
            assertFalse(active.isFinishing());
            assertTrue("Home/background must keep playing", onMain(session::isPlaying));
            openShortcut(context, shortcut, fromMedia);
            Activity resumed = awaitFocus(observed, MusicPlayerActivity.class);
            assertEquals("music:" + selected.id, onMain(() -> session.getCurrentMediaItem().mediaId));
            assertTrue(onMain(session::isPlaying));
            int restoredTask = resumed.getTaskId();
            swipeRight(resumed);
            Activity library = awaitFocus(observed, MusicLibraryActivity.class);
            assertEquals(restoredTask, library.getTaskId());
            waitFor(
                    () -> onMain(() -> !session.isPlaying() && session.getMediaItemCount() == 0),
                    5000);
            assertTrue(resumed.isFinishing());
            swipeRight(library);
            Activity home = awaitFocus(observed, MainActivity.class);
            assertEquals(restoredTask, home.getTaskId());
        } finally {
            getInstrumentation().runOnMainSync(() -> {
                application.unregisterActivityLifecycleCallbacks(observed);
                observed.finishCreated();
            });
            getInstrumentation().waitForIdleSync();
            if (future != null) {
                ListenableFuture<MediaController> release = future;
                getInstrumentation().runOnMainSync(() -> MediaController.releaseFuture(release));
            }
            db.putSetting("playback_speed", oldSpeed);
            db.putSetting("playback_mode", oldMode);
            File fixture = new File(MusicScanner.musicDir(context), "device-background.m4a");
            if (fixture.isFile()) {
                assertTrue(fixture.delete());
            }
            MusicScanner.scan(context, db);
            db.close();
        }
    }

    private Activity awaitFocus(ObservedActivities observed, Class<? extends Activity> type) throws Exception {
        AtomicReference<Activity> found = new AtomicReference<>();
        waitFor(() -> onMain(() -> {
            Activity current = observed.resumed;
            if (type.isInstance(current) && current.hasWindowFocus()) {
                found.set(current);
            }
            return found.get() != null;
        }), 8000);
        return found.get();
    }

    private static void openShortcut(Context context, PendingIntent shortcut, boolean fromMedia)
            throws PendingIntent.CanceledException {
        if (fromMedia) {
            shortcut.send();
            return;
        }
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(context, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void swipeRight(Activity activity) {
        getInstrumentation().runOnMainSync(() -> {
            long time = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 80, 230, 0);
            MotionEvent up = MotionEvent.obtain(time, time + 200, MotionEvent.ACTION_UP, 230, 230, 0);
            try {
                activity.dispatchTouchEvent(down);
                activity.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
        });
    }

    private static final class ObservedActivities implements Application.ActivityLifecycleCallbacks {
        private final List<Activity> created = new ArrayList<>();
        private Activity resumed;

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            created.add(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) { }

        @Override
        public void onActivityResumed(Activity activity) {
            resumed = activity;
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (resumed == activity) {
                resumed = null;
            }
        }

        @Override
        public void onActivityStopped(Activity activity) { }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

        @Override
        public void onActivityDestroyed(Activity activity) {
            created.remove(activity);
        }

        void finishCreated() {
            for (Activity activity : new ArrayList<>(created)) {
                if (!activity.isFinishing()) {
                    activity.finish();
                }
            }
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

    private static String text(Activity activity, int id) {
        CharSequence value = ((TextView) activity.findViewById(id)).getText();
        return value == null ? "" : value.toString();
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
        fail("Timed out waiting for focus lifecycle state");
    }

    private <T> T onMain(Value<T> source) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(source.get()));
        return value.get();
    }

    private interface Check {

        boolean ok() throws Exception;
    }

    private interface Value<T> {

        T get();
    }
}
