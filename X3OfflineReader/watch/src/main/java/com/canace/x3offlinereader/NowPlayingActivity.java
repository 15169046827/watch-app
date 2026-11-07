package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;

/** Invisible trampoline used only by the system's active-player shortcut. */
public final class NowPlayingActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent target = PlaybackTargetStore.activeDestination(this);
        if (target == null) {
            startActivity(MainActivity.homeIntent(this).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } else if (isTaskRoot()) {
            startActivities(new Intent[] {MainActivity.homeIntent(this),
                    PlaybackTargetStore.libraryFor(this, target), target});
        } else {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        finish();
    }
}
