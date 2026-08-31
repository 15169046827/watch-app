package com.canace.x3offlinereader;

import android.content.Intent;
import android.os.Bundle;
import com.canace.x3offlinereader.music.MusicLibraryActivity;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.canace.x3offlinereader.ui.SwipeBackActivity;

/** Product-level entry point: novel and music are equal, isolated modes. */
public final class MainActivity extends SwipeBackActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);if(routeToActivePlayer())return;setContentView(R.layout.activity_main);enableSwipeBack(R.id.root);findViewById(R.id.books_button).setOnClickListener(v->startActivity(new Intent(this,BookLibraryActivity.class)));findViewById(R.id.music_button).setOnClickListener(v->startActivity(new Intent(this,MusicLibraryActivity.class)));}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);routeToActivePlayer();}
    private boolean routeToActivePlayer(){Intent target=PlaybackTargetStore.activeDestination(this);if(target==null)return false;startActivity(target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();return true;}
}
