package com.canace.x3offlinereader.music;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.playback.AudioOutputControls;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.canace.x3offlinereader.ui.HorizontalPager;
import com.canace.x3offlinereader.ui.PageIndicatorView;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import com.canace.x3offlinereader.ui.SwipeBackActivity;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MusicPlayerActivity extends SwipeBackActivity {

    private static final String[] MODE_VALUES = {
        "stop_after_track", "repeat_one", "repeat_all", "shuffle"
    };

    private static final int[] MODE_LABELS = {
        R.string.mode_stop_after_track,
        R.string.mode_repeat_one,
        R.string.mode_repeat_all,
        R.string.mode_shuffle
    };

    private static final int[] MODE_ICONS = {
        R.drawable.ic_mode_stop_after,
        R.drawable.ic_mode_repeat_one,
        R.drawable.ic_mode_repeat_all,
        R.drawable.ic_mode_shuffle
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker =
            new Runnable() {

                @Override
                public void run() {
                    update();
                    handler.postDelayed(this, 250);
                }
            };

    private MusicLibraryDb db;

    private List<MusicModels.Track> tracks;

    private ListenableFuture<MediaController> future;

    private MediaController controller;

    private TextView title;

    private TextView fullLyrics;

    private TextView position;

    private PageIndicatorView indicator;

    private ScrollView lyricScroll;

    private ImageButton play;

    private ImageButton modeButton;

    private HorizontalPager pager;

    private LrcReader lrc = new LrcReader(null);

    private int lastIndex = -1;

    private int activeLyric = Integer.MIN_VALUE;

    private int lyricTouchSlop;

    private long lastPersisted;

    private float lyricDownX;

    private float lyricDownY;

    private boolean lyricsBrowsed;

    private boolean verticalLyricGesture;

    private boolean destroyed;

    private String playbackMode;

    private String targetOwner;

    private AudioOutputControls audioControls;

    private final Runnable resumeLyricFollow =
            () -> {
                if (!destroyed && controller != null && controller.isPlaying()) {
                    lyricsBrowsed = false;
                    centerActiveLyric();
                }
            };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_music_player);
        enableSwipeBack(R.id.root);
        db = new MusicLibraryDb(this);
        tracks = db.tracks();
        int selected = selectedTrack(getIntent().getLongExtra("track_id", -1));
        bindViews();
        configureControls();
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        targetOwner = PlaybackTargetStore.beginMusic(this, tracks.get(selected).id);
        connectController(selected);
    }

    private int selectedTrack(long wanted) {
        int selected = 0;
        for (int index = 0; index < tracks.size(); index++) {
            if (tracks.get(index).id == wanted) {
                selected = index;
            }
        }
        return selected;
    }

    private void bindViews() {
        title = findViewById(R.id.title);
        title.setSelected(true);
        fullLyrics = findViewById(R.id.full_lyrics);
        lyricScroll = findViewById(R.id.lyric_scroll);
        lyricTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        lyricScroll.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateLyricInsets());
        lyricScroll.setOnTouchListener((view, event) -> handleLyricTouch(event));
        position = findViewById(R.id.position);
        indicator = findViewById(R.id.page_indicator);
        pager = findViewById(R.id.player_pager);
        pager.setListener(this::renderPage);
        play = findViewById(R.id.play_button);
        modeButton = findViewById(R.id.mode_button);
        audioControls =
                new AudioOutputControls(
                        this, findViewById(R.id.volume_button), findViewById(R.id.output_button));
    }

    private void configureControls() {
        playbackMode = parseMode(db.settingValue("playback_mode", MODE_VALUES[0]));
        refreshMode();
        setControlsEnabled(false);
        play.setOnClickListener(view -> togglePlay());
        findViewById(R.id.previous_button).setOnClickListener(view -> seekPrevious());
        findViewById(R.id.next_button).setOnClickListener(view -> seekNext());
        modeButton.setOnClickListener(view -> showModePicker());
    }

    private void togglePlay() {
        if (controller == null) {
            return;
        }
        if (controller.isPlaying()) {
            controller.pause();
        } else {
            resumeFollowingLyrics();
            controller.play();
        }
    }

    private void seekPrevious() {
        if (controller != null) {
            controller.seekToPreviousMediaItem();
        }
    }

    private void seekNext() {
        if (controller != null) {
            controller.seekToNextMediaItem();
        }
    }

    private void connectController(int start) {
        future =
                new MediaController.Builder(
                                this,
                                new SessionToken(
                                        this, new ComponentName(this, PlaybackService.class)))
                        .buildAsync();
        future.addListener(() -> finishControllerSetup(start), MoreExecutors.directExecutor());
    }

    private void finishControllerSetup(int start) {
        try {
            controller = future.get();
            if (destroyed) {
                return;
            }
            configureController(start);
        } catch (Exception error) {
            showNoTracks();
        }
    }

    private void configureController(int start) {
        controller.pause();
        controller.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                true);
        controller.setPlaybackSpeed(1f);
        applyPlaybackMode();
        controller.setMediaItems(mediaItems(), start, db.progress(tracks.get(start).id));
        controller.addListener(playerListener(start));
        controller.prepare();
        handler.post(ticker);
    }

    private List<MediaItem> mediaItems() {
        List<MediaItem> items = new ArrayList<>();
        for (MusicModels.Track track : tracks) {
            items.add(
                    new MediaItem.Builder()
                            .setMediaId(mediaId(track))
                            .setUri(Uri.fromFile(new File(track.path)))
                            .build());
        }
        return items;
    }

    private Player.Listener playerListener(int start) {
        return new Player.Listener() {

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (destroyed) {
                    return;
                }
                if (playing) {
                    resumeFollowingLyrics();
                } else {
                    persistCurrent();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (!destroyed) {
                    maybeReady(start);
                }
            }

            @Override
            public void onPositionDiscontinuity(
                    Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition,
                    int reason) {
                if (!destroyed
                        && oldPosition.mediaItemIndex >= 0
                        && oldPosition.mediaItemIndex < tracks.size()) {
                    db.saveProgress(
                            tracks.get(oldPosition.mediaItemIndex).id, oldPosition.positionMs);
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                if (!destroyed
                        && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        && MODE_VALUES[0].equals(playbackMode)) {
                    controller.pause();
                }
            }

            @Override
            public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
                if (!destroyed) {
                    maybeReady(start);
                }
            }
        };
    }

    private void showNoTracks() {
        if (!destroyed) {
            runOnUiThread(
                    () -> Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_LONG).show());
        }
    }
    private MusicModels.Track current() {
        return controller == null ? null : track(controller.getCurrentMediaItem());
    }

    private MusicModels.Track track(MediaItem item) {
        if (item == null) {
            return null;
        }
        for (MusicModels.Track track : tracks) {
            if (mediaId(track).equals(item.mediaId)) {
                return track;
            }
        }
        return null;
    }

    private void maybeReady(int selected) {
        if (readyQueue(selected)) {
            setControlsEnabled(true);
        }
    }

    private boolean readyQueue(int selected) {
        MediaItem current = controller.getCurrentMediaItem();
        return controller.getPlaybackState() != Player.STATE_IDLE
                && controller.getMediaItemCount() == tracks.size()
                && controller.getCurrentMediaItemIndex() == selected
                && current != null
                && mediaId(tracks.get(selected)).equals(current.mediaId);
    }

    private static String mediaId(MusicModels.Track track) {
        return "music:" + track.id;
    }

    private void updateTrack() {
        MusicModels.Track track = current();
        if (track == null) {
            return;
        }
        PlaybackTargetStore.music(this, track.id, targetOwner);
        title.setText(track.title);
        lrc = new LrcReader(track.lrcPath);
        activeLyric = Integer.MIN_VALUE;
        lyricsBrowsed = false;
        fullLyrics.setText(
                lrc.fullText().isEmpty() ? getString(R.string.no_lyrics) : lrc.fullText());
        lastPersisted = controller.getCurrentPosition();
    }

    private void refreshMode() {
        int index = modeIndex();
        modeButton.setImageResource(MODE_ICONS[index]);
        modeButton.setContentDescription(getString(MODE_LABELS[index]));
    }

    private void setControlsEnabled(boolean enabled) {
        findViewById(R.id.previous_button).setEnabled(enabled);
        play.setEnabled(enabled);
        findViewById(R.id.next_button).setEnabled(enabled);
        modeButton.setEnabled(enabled);
    }

    private int modeIndex() {
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i].equals(playbackMode)) {
                return i;
            }
        }
        return 0;
    }

    private void showModePicker() {
        String[] labels = new String[MODE_LABELS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(MODE_LABELS[i]);
        }
        RoundChoiceDialog.show(
                this,
                getText(R.string.mode_title),
                labels,
                modeIndex(),
                which -> {
                    playbackMode = MODE_VALUES[which];
                    db.putSetting("playback_mode", playbackMode);
                    applyPlaybackMode();
                    refreshMode();
                });
    }

    private void applyPlaybackMode() {
        if (controller != null) {
            boolean shuffle = MODE_VALUES[3].equals(playbackMode);
            controller.setShuffleModeEnabled(shuffle);
            controller.setRepeatMode(
                    MODE_VALUES[1].equals(playbackMode)
                            ? Player.REPEAT_MODE_ONE
                            : MODE_VALUES[2].equals(playbackMode) || shuffle
                                    ? Player.REPEAT_MODE_ALL
                                    : Player.REPEAT_MODE_OFF);
        }
    }

    private void update() {
        if (destroyed || controller == null) {
            return;
        }
        if (lastIndex != controller.getCurrentMediaItemIndex()) {
            lastIndex = controller.getCurrentMediaItemIndex();
            updateTrack();
        }
        long ms = controller.getCurrentPosition();
        boolean playing = controller.isPlaying();
        play.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        play.setContentDescription(getString(playing ? R.string.pause : R.string.play));
        renderLyrics(ms);
        position.setText(
                getString(
                        R.string.playback_position,
                        format(ms),
                        format(Math.max(0, controller.getDuration()))));
        if (Math.abs(ms - lastPersisted) >= 5000) {
            MusicModels.Track track = current();
            if (track != null) {
                db.saveProgress(track.id, ms);
            }
            lastPersisted = ms;
        }
    }

    private void renderLyrics(long positionMs) {
        int next = lrc.activeIndex(positionMs);
        if (next == activeLyric) {
            return;
        }
        activeLyric = next;
        String text = lrc.fullText();
        if (text.isEmpty()) {
            fullLyrics.setText(R.string.no_lyrics);
            return;
        }
        int savedY = lyricScroll.getScrollY();
        SpannableString styled = new SpannableString(text);
        if (next >= 0) {
            int start = lrc.activeStart(next);
            int end = lrc.activeEnd(next);
            styled.setSpan(
                    new ForegroundColorSpan(getColor(R.color.accent)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(
                    new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(
                    new RelativeSizeSpan(1.12f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        fullLyrics.setText(styled);
        if (lyricsBrowsed) {
            lyricScroll.post(() -> lyricScroll.scrollTo(0, savedY));
        } else {
            centerActiveLyric();
        }
    }

    private void updateLyricInsets() {
        int inset = lyricScroll.getHeight() / 2;
        if (inset <= 0 || fullLyrics.getPaddingTop() == inset) {
            return;
        }
        fullLyrics.setPadding(
                fullLyrics.getPaddingLeft(), inset, fullLyrics.getPaddingRight(), inset);
        if (!lyricsBrowsed) {
            centerActiveLyric();
        }
    }

    private void centerActiveLyric() {
        if (activeLyric < 0) {
            return;
        }
        fullLyrics.post(
                () -> {
                    if (fullLyrics.getLayout() == null) {
                        return;
                    }
                    int line =
                            fullLyrics.getLayout().getLineForOffset(lrc.activeStart(activeLyric));
                    int lineCenter =
                            fullLyrics.getTop()
                                    + fullLyrics.getCompoundPaddingTop()
                                    + (fullLyrics.getLayout().getLineTop(line)
                                                    + fullLyrics.getLayout().getLineBottom(line))
                                            / 2;
                    int max = Math.max(0, fullLyrics.getHeight() - lyricScroll.getHeight());
                    lyricScroll.smoothScrollTo(
                            0,
                            Math.max(0, Math.min(max, lineCenter - lyricScroll.getHeight() / 2)));
                });
    }

    private boolean handleLyricTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lyricDownX = event.getX();
            lyricDownY = event.getY();
            verticalLyricGesture = false;
            handler.removeCallbacks(resumeLyricFollow);
        } else {
            if (action == MotionEvent.ACTION_MOVE && !verticalLyricGesture) {
                float dx = Math.abs(event.getX() - lyricDownX);
                float dy = Math.abs(event.getY() - lyricDownY);
                if (dy > lyricTouchSlop && dy > dx) {
                    verticalLyricGesture = true;
                    lyricsBrowsed = true;
                }
            } else {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (verticalLyricGesture && controller != null && controller.isPlaying()) {
                        handler.postDelayed(resumeLyricFollow, 4000);
                    }
                    verticalLyricGesture = false;
                }
            }
        }
        return false;
    }

    private void resumeFollowingLyrics() {
        handler.removeCallbacks(resumeLyricFollow);
        lyricsBrowsed = false;
        centerActiveLyric();
    }

    private void persistCurrent() {
        if (destroyed) {
            return;
        }
        MusicModels.Track track = current();
        if (controller != null && track != null) {
            db.saveProgress(track.id, controller.getCurrentPosition());
            lastPersisted = controller.getCurrentPosition();
        }
    }

    private static String format(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private static String parseMode(String value) {
        for (String candidate : MODE_VALUES) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        return MODE_VALUES[0];
    }

    private void renderPage(int page) {
        indicator.setCurrentPage(page);
        indicator.setContentDescription(
                getString(
                        page == 0
                                ? R.string.controls_page_description
                                : R.string.lyrics_page_description));
    }

    @Override
    protected boolean canSwipeBack() {
        return pager == null || pager.currentPage() == 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        audioControls.start();
    }

    @Override
    protected void onStop() {
        audioControls.stop();
        super.onStop();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return audioControls != null && audioControls.handleCrown(event)
                || super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus && audioControls != null) {
            audioControls.resetCrown();
        }
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onPause() {
        persistCurrent();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (!isChangingConfigurations()
                && PlaybackTargetStore.clearIfOwner(this, targetOwner)
                && controller != null) {
            controller.stop();
            controller.clearMediaItems();
        }
        if (future != null) {
            MediaController.releaseFuture(future);
        }
        db.close();
        super.onDestroy();
    }
}
