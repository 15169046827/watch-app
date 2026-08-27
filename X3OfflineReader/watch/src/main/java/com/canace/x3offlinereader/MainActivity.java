package com.canace.x3offlinereader;

import android.content.Intent;
import android.os.Bundle;
import com.canace.x3offlinereader.music.MusicLibraryActivity;
import com.canace.x3offlinereader.ui.SwipeBackActivity;

/** Product-level entry point: novel and music are equal, isolated modes. */
public final class MainActivity extends SwipeBackActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_main);enableSwipeBack(R.id.root);findViewById(R.id.books_button).setOnClickListener(v->startActivity(new Intent(this,BookLibraryActivity.class)));findViewById(R.id.music_button).setOnClickListener(v->startActivity(new Intent(this,MusicLibraryActivity.class)));}
}
