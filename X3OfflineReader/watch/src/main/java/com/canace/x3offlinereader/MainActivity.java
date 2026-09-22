package com.canace.x3offlinereader;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.canace.x3offlinereader.music.MusicLibraryActivity;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.canace.x3offlinereader.ui.SwipeBackActivity;

/** Product-level entry point: novel and music are equal, isolated modes. */
public final class MainActivity extends SwipeBackActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (routeToActivePlayer()) {
            return;
        }
        setContentView(R.layout.activity_main);
        enableSwipeBack(R.id.root);
        findViewById(R.id.books_button)
                .setOnClickListener(
                        v -> startActivity(new Intent(this, BookLibraryActivity.class)));
        findViewById(R.id.music_button)
                .setOnClickListener(
                        v -> startActivity(new Intent(this, MusicLibraryActivity.class)));
        findViewById(R.id.data_button)
                .setOnClickListener(
                        v -> startActivity(new Intent(this, DataManagementActivity.class)));
        showDataNoticeIfNeeded();
    }

    private void showDataNoticeIfNeeded() {
        if (getSharedPreferences("data_notice", Context.MODE_PRIVATE)
                .getBoolean("accepted", false)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("离线数据说明")
                .setMessage("听读不联网。源 .opbook、音乐和歌词由你保管；手表只保存目录索引、播放缓存、进度、收藏和设置。备份未加密，不含源文件；清除本机数据不会删除源文件。")
                .setCancelable(false)
                .setPositiveButton("知道了", (dialog, which) ->
                        getSharedPreferences("data_notice", Context.MODE_PRIVATE)
                                .edit().putBoolean("accepted", true).apply())
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        routeToActivePlayer();
    }

    private boolean routeToActivePlayer() {
        Intent target = PlaybackTargetStore.activeDestination(this);
        if (target == null) {
            return false;
        }
        startActivity(
                target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
        return true;
    }
}
