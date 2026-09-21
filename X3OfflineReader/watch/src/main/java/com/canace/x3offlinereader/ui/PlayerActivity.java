package com.canace.x3offlinereader.ui;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
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
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookMediaCache;
import com.canace.x3offlinereader.importer.BookSourceAccess;
import com.canace.x3offlinereader.playback.AudioOutputControls;
import com.canace.x3offlinereader.playback.CrownMotionEvents;
import com.canace.x3offlinereader.playback.CrownStepAccumulator;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackSpeedRange;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends SwipeBackActivity {

    private static final class QueueTrack {

        final int chapterIndex;

        final LibraryModels.ChapterSummary chapter;

        final LibraryModels.TrackSummary track;

        QueueTrack(
                int chapterIndex,
                LibraryModels.ChapterSummary chapter,
                LibraryModels.TrackSummary track) {
            this.chapterIndex = chapterIndex;
            this.chapter = chapter;
            this.track = track;
        }
    }

    private static final class TextTarget {

        final int start;

        final int end;

        final int queueIndex;

        final long positionMs;

        TextTarget(int start, int end, int queueIndex, long positionMs) {
            this.start = start;
            this.end = end;
            this.queueIndex = queueIndex;
            this.positionMs = positionMs;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker =
            new Runnable() {

                @Override
                public void run() {
                    update();
                    handler.postDelayed(this, 250);
                }
            };

    private final CrownStepAccumulator readerCrown = new CrownStepAccumulator();

    private ListenableFuture<MediaController> future;

    private MediaController controller;

    private TimelineReader timeline;

    private LibraryDb db;

    private final List<LibraryModels.ChapterSummary> chapters = new ArrayList<>();

    private final List<QueueTrack> queue = new ArrayList<>();

    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean preparing;

    private volatile boolean prefetching;

    private volatile boolean controllerReady;

    private volatile boolean destroyed;

    private String bookId;

    private String targetOwner;

    private TextView title;

    private TextView position;

    private TextView fullText;

    private PageIndicatorView indicator;

    private ScrollView readerScroll;

    private ImageButton play;

    private ImageButton speedButton;

    private ImageButton previous;

    private ImageButton next;

    private ImageButton directory;

    private Button playFromHere;

    private HorizontalPager pager;

    private final List<TextTarget> textTargets = new ArrayList<>();

    private int currentIndex = -1;

    private int activeSegment = -1;

    private int browseQueueIndex = -1;

    private int browseStartScrollY;

    private long lastPersisted;

    private long browsePositionMs;

    private float speed;

    private boolean browsingText;

    private AudioOutputControls audioControls;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        enableSwipeBack(R.id.root);
        bookId = getIntent().getStringExtra("book_id");
        String exactTrack = getIntent().getStringExtra("start_track_id");
        long exactPosition = getIntent().getLongExtra("start_position_ms", -1);
        String wantedChapter = wantedChapter(exactTrack);
        db = new LibraryDb(this);
        loadQueue();
        bindViews();
        configureControls();
        if (queue.isEmpty()) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        LibraryModels.ResumePoint resume = resumePoint(exactTrack, wantedChapter);
        int start = findStart(wantedChapter, resume);
        long startPosition = startPosition(exactTrack, exactPosition, resume, start);
        targetOwner = PlaybackTargetStore.beginBook(this, bookId, queue.get(start).track.id);
        int selected = start;
        prepareWindow(
                queue.get(start).chapterIndex, true, () -> initController(selected, startPosition));
    }

    private String wantedChapter(String exactTrack) {
        if (exactTrack != null) {
            return exactTrack;
        }
        String chapter = getIntent().getStringExtra("chapter_id");
        return chapter == null ? getIntent().getStringExtra("track_id") : chapter;
    }

    private void loadQueue() {
        chapters.addAll(db.chapters(bookId));
        for (int chapterIndex = 0; chapterIndex < chapters.size(); chapterIndex++) {
            LibraryModels.ChapterSummary chapter = chapters.get(chapterIndex);
            for (LibraryModels.TrackSummary track : chapter.tracks) {
                queue.add(new QueueTrack(chapterIndex, chapter, track));
            }
        }
    }

    private void bindViews() {
        title = findViewById(R.id.title);
        title.setSelected(true);
        position = findViewById(R.id.position);
        fullText = findViewById(R.id.full_text);
        readerScroll = findViewById(R.id.reader_scroll);
        indicator = findViewById(R.id.page_indicator);
        pager = findViewById(R.id.player_pager);
        pager.setListener(this::renderPage);
        play = findViewById(R.id.play_button);
        previous = findViewById(R.id.previous_button);
        next = findViewById(R.id.next_button);
        playFromHere = findViewById(R.id.play_from_here_button);
        directory = findViewById(R.id.directory_button);
        speedButton = findViewById(R.id.speed_button);
        audioControls =
                new AudioOutputControls(
                        this, findViewById(R.id.volume_button), findViewById(R.id.output_button));
    }

    private void configureControls() {
        speed = parseSpeed(db.settingValue("playback_speed", "1.0"));
        db.putSetting("playback_speed", Float.toString(speed));
        refreshSpeed();
        setPreparing(true);
        play.setOnClickListener(view -> togglePlayback());
        previous.setOnClickListener(view -> seekChapter(-1));
        next.setOnClickListener(view -> seekChapter(1));
        playFromHere.setOnClickListener(view -> playFromVisibleText());
        directory.setOnClickListener(view -> showDirectory());
        speedButton.setOnClickListener(view -> showSpeedPicker());
        readerScroll.setOnTouchListener((view, event) -> handleReaderTouch(event));
        readerScroll.setOnScrollChangeListener(
                (view, x, y, oldX, oldY) -> updateVisibleTextAfterScroll());
    }

    private void updateVisibleTextAfterScroll() {
        if (browsingText && controller != null && !controller.isPlaying()) {
            readerScroll.post(this::selectVisibleText);
        }
    }

    private LibraryModels.ResumePoint resumePoint(String exactTrack, String wantedChapter) {
        if (exactTrack != null) {
            return null;
        }
        if (getIntent().getBooleanExtra("resume_latest", false) || wantedChapter == null) {
            return db.latestProgress(bookId);
        }
        return db.chapterProgress(bookId, wantedChapter);
    }

    private long startPosition(
            String exactTrack,
            long exactPosition,
            LibraryModels.ResumePoint resume,
            int start) {
        QueueTrack item = queue.get(start);
        if (exactTrack != null && item.track.id.equals(exactTrack) && exactPosition >= 0) {
            return Math.min(exactPosition, Math.max(0, item.track.durationMs - 1));
        }
        if (resume != null && item.track.id.equals(resume.trackId)) {
            return resume.trackPositionMs;
        }
        return 0;
    }
    private void initController(int selected, long startPosition) {
        future =
                new MediaController.Builder(
                                this,
                                new SessionToken(
                                        this, new ComponentName(this, PlaybackService.class)))
                        .buildAsync();
        future.addListener(
                () -> {
                    if (destroyed) {
                        return;
                    }
                    try {
                        controller = future.get();
                        if (destroyed) {
                            return;
                        }
                        controller.pause();
                        controller.setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(C.USAGE_MEDIA)
                                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                                        .build(),
                                true);
                        controller.setPlaybackSpeed(speed);
                        controller.setRepeatMode(Player.REPEAT_MODE_OFF);
                        controller.setShuffleModeEnabled(false);
                        List<MediaItem> items = new ArrayList<>();
                        for (QueueTrack item : queue) {
                            items.add(
                                    new MediaItem.Builder()
                                            .setMediaId(mediaId(item))
                                            .setUri(Uri.fromFile(new File(item.track.audioPath)))
                                            .build());
                        }
                        controller.setMediaItems(items, selected, startPosition);
                        controller.addListener(
                                new Player.Listener() {

                                    @Override
                                    public void onIsPlayingChanged(boolean playing) {
                                        if (!playing) {
                                            persistCurrent();
                                        }
                                    }

                                    @Override
                                    public void onPlaybackStateChanged(int state) {
                                        maybeReady(selected);
                                    }

                                    @Override
                                    public void onPositionDiscontinuity(
                                            Player.PositionInfo oldPosition,
                                            Player.PositionInfo newPosition,
                                            int reason) {
                                        persist(oldPosition.mediaItemIndex, oldPosition.positionMs);
                                    }

                                    @Override
                                    public void onMediaItemTransition(MediaItem item, int reason) {
                                        updateTrack();
                                    }

                                    @Override
                                    public void onTimelineChanged(
                                            androidx.media3.common.Timeline timeline, int reason) {
                                        maybeReady(selected);
                                    }
                                });
                        controller.prepare();
                        handler.post(ticker);
                    } catch (Exception error) {
                        if (!destroyed) {
                            runOnUiThread(
                                    () -> {
                                        if (!destroyed) {
                                            Toast.makeText(
                                                            this,
                                                            R.string.no_tracks,
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                        }
                                    });
                        }
                    }
                },
                MoreExecutors.directExecutor());
    }

    private void maybeReady(int selected) {
        if (!destroyed && readyQueue(selected)) {
            controllerReady = true;
            setPreparing(false);
        }
    }

    private boolean readyQueue(int selected) {
        MediaItem current = controller.getCurrentMediaItem();
        return controller.getPlaybackState() != Player.STATE_IDLE
                && controller.getMediaItemCount() == queue.size()
                && controller.getCurrentMediaItemIndex() == selected
                && current != null
                && mediaId(queue.get(selected)).equals(current.mediaId);
    }

    private String mediaId(QueueTrack item) {
        return "book:" + bookId + ":" + item.track.id;
    }

    private int findStart(String wanted, LibraryModels.ResumePoint resume) {
        String track = resume == null ? null : resume.trackId;
        for (int i = 0; i < queue.size(); i++) {
            if (track != null && track.equals(queue.get(i).track.id)) {
                return i;
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            if (wanted != null
                    && (wanted.equals(queue.get(i).chapter.id)
                            || wanted.equals(queue.get(i).track.id))) {
                return i;
            }
        }
        return 0;
    }

    private void refreshSpeed() {
        speedButton.setContentDescription(
                getString(R.string.speed_description, PlaybackSpeedRange.label(speed)));
    }

    private void showSpeedPicker() {
        RoundSpeedDialog.show(
                this,
                speed,
                value -> {
                    speed = PlaybackSpeedRange.normalize(value);
                    db.putSetting("playback_speed", Float.toString(speed));
                    if (controller != null) {
                        controller.setPlaybackSpeed(speed);
                    }
                    refreshSpeed();
                });
    }

    private QueueTrack current() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
    }

    private void updateTrack() {
        if (destroyed || controller == null) {
            return;
        }
        currentIndex = controller.getCurrentMediaItemIndex();
        QueueTrack item = current();
        if (item == null) {
            return;
        }
        PlaybackTargetStore.book(this, bookId, item.track.id, targetOwner);
        try {
            timeline = new TimelineReader(item.track.timelinePath, item.chapter.contentPath);
            title.setText(item.chapter.title);
            activeSegment = -1;
            fullText.setText(timeline.fullText);
            lastPersisted = controller.getCurrentPosition();
            hidePlayFromHere();
            rebuildTextTargets(item.chapterIndex);
            updateText(true);
            prepareWindow(item.chapterIndex, false, null);
        } catch (Exception error) {
            if (!destroyed) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void seekChapter(int delta) {
        QueueTrack item = current();
        if (controller == null || item == null || preparing) {
            return;
        }
        int target = Math.max(0, Math.min(chapters.size() - 1, item.chapterIndex + delta));
        if (target == item.chapterIndex) {
            if (delta < 0) {
                controller.seekTo(0);
            }
            return;
        }
        prepareWindow(
                target,
                true,
                () -> {
                    for (int i = 0; i < queue.size(); i++) {
                        if (queue.get(i).chapterIndex == target) {
                            LibraryModels.ResumePoint saved =
                                    db.chapterProgress(bookId, queue.get(i).chapter.id);
                            int index =
                                    saved == null ? i : findStart(queue.get(i).chapter.id, saved);
                            controller.seekTo(index, saved == null ? 0 : saved.trackPositionMs);
                            return;
                        }
                    }
                });
    }

    private void togglePlayback() {
        if (controller == null || preparing) {
            return;
        }
        if (controller.isPlaying()) {
            controller.pause();
        } else {
            hidePlayFromHere();
            controller.play();
            updateText(true);
        }
        update();
    }

    private boolean handleReaderTouch(MotionEvent event) {
        if (controller != null && controller.isPlaying()) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            browsingText = true;
            browseStartScrollY = readerScroll.getScrollY();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean moved = Math.abs(readerScroll.getScrollY() - browseStartScrollY) > 4;
            browsingText = false;
            if (moved) {
                readerScroll.post(this::selectVisibleText);
            }
        }
        return false;
    }

    private void showDirectory() {
        QueueTrack item = current();
        if (item == null) {
            return;
        }
        String[] labels = new String[chapters.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = ChapterLabels.numbered(i + 1, chapters.get(i).title);
        }
        RoundChoiceDialog.show(
                this,
                getText(R.string.chapters),
                labels,
                item.chapterIndex,
                which -> seekChapter(which - item.chapterIndex));
    }

    private void rebuildTextTargets(int chapterIndex) {
        textTargets.clear();
        for (int i = 0; i < queue.size(); i++) {
            QueueTrack item = queue.get(i);
            if (item.chapterIndex != chapterIndex) {
                continue;
            }
            try {
                TimelineReader source =
                        i == currentIndex
                                ? timeline
                                : new TimelineReader(
                                        item.track.timelinePath, item.chapter.contentPath);
                for (TimelineReader.Segment segment : source.segments) {
                    textTargets.add(
                            new TextTarget(
                                    segment.displayStart, segment.displayEnd, i, segment.start));
                }
            } catch (Exception ignored) {
            }
        }
        Collections.sort(textTargets, (left, right) -> Integer.compare(left.start, right.start));
    }

    private void selectVisibleText() {
        if (controller == null
                || controller.isPlaying()
                || timeline == null
                || textTargets.isEmpty()
                || fullText.getLayout() == null) {
            return;
        }
        int y =
                readerScroll.getScrollY()
                        + readerScroll.getHeight() / 2
                        - fullText.getTop()
                        - fullText.getCompoundPaddingTop();
        int line = fullText.getLayout().getLineForVertical(Math.max(0, y));
        int offset = fullText.getLayout().getLineStart(line);
        TextTarget selected = textTargets.get(0);
        int distance = Integer.MAX_VALUE;
        for (TextTarget target : textTargets) {
            if (offset >= target.start && offset <= target.end) {
                selected = target;
                distance = 0;
                break;
            }
            int current = Math.min(Math.abs(offset - target.start), Math.abs(offset - target.end));
            if (current < distance) {
                distance = current;
                selected = target;
            }
        }
        browseQueueIndex = selected.queueIndex;
        browsePositionMs = selected.positionMs;
        playFromHere.setVisibility(View.VISIBLE);
        playFromHere.setEnabled(!preparing);
    }

    private void playFromVisibleText() {
        if (controller == null || preparing || browseQueueIndex < 0) {
            return;
        }
        controller.seekTo(browseQueueIndex, browsePositionMs);
        hidePlayFromHere();
        controller.play();
        pager.setCurrentPage(1, true);
        update();
    }

    private void hidePlayFromHere() {
        browseQueueIndex = -1;
        browsePositionMs = 0;
        playFromHere.setVisibility(View.GONE);
    }

    private List<LibraryModels.TrackSummary> window(int chapterIndex) {
        List<LibraryModels.TrackSummary> result = new ArrayList<>();
        for (int i = Math.max(0, chapterIndex - 1);
                i <= Math.min(chapters.size() - 1, chapterIndex + 1);
                i++) {
            result.addAll(chapters.get(i).tracks);
        }
        return result;
    }

    private void prepareWindow(int chapterIndex, boolean blocking, Runnable complete) {
        if (!canPrepare(blocking)) {
            return;
        }
        LibraryModels.BookStorage storage = db.storage(bookId);
        if (storage == null) {
            return;
        }
        List<LibraryModels.TrackSummary> tracks = window(chapterIndex);
        if (!storage.indexedPackage()) {
            runCompletion(complete);
            return;
        }
        markPreparation(blocking, true);
        boolean accepted =
                LifecycleExecutor.execute(
                        mediaExecutor,
                        () -> prepareInBackground(storage, tracks, blocking, complete));
        if (!accepted && !destroyed) {
            markPreparation(blocking, false);
        }
    }

    private boolean canPrepare(boolean blocking) {
        return !destroyed
                && !mediaExecutor.isShutdown()
                && !preparing
                && (blocking || !prefetching);
    }

    private void runCompletion(Runnable complete) {
        if (complete != null && !destroyed) {
            complete.run();
        }
    }

    private void markPreparation(boolean blocking, boolean active) {
        if (blocking) {
            preparing = active;
            setPreparing(active);
        } else {
            prefetching = active;
        }
    }

    private void prepareInBackground(
            LibraryModels.BookStorage storage,
            List<LibraryModels.TrackSummary> tracks,
            boolean blocking,
            Runnable complete) {
        Exception failure = prepareMedia(storage, tracks);
        runOnUiThread(() -> finishPreparation(blocking, complete, failure));
    }

    private Exception prepareMedia(
            LibraryModels.BookStorage storage, List<LibraryModels.TrackSummary> tracks) {
        try {
            if (!BookSourceAccess.exists(this, storage.sourceUri)) {
                throw new BookSourceAccess.SourceMissingException();
            }
            BookMediaCache.prepare(this, bookId, storage.sourceUri, storage.rootPath, tracks);
            BookMediaCache.retain(this, bookId, tracks);
            return null;
        } catch (Exception error) {
            return error;
        }
    }

    private void finishPreparation(
            boolean blocking, Runnable complete, Exception failure) {
        if (destroyed) {
            return;
        }
        markPreparation(blocking, false);
        if (failure == null) {
            runCompletion(complete);
        } else {
            Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show();
            if (controller == null) {
                finish();
            }
        }
    }
    private void setPreparing(boolean value) {
        boolean enabled = !value && controllerReady;
        play.setEnabled(enabled);
        previous.setEnabled(enabled);
        next.setEnabled(enabled);
        directory.setEnabled(enabled);
        speedButton.setEnabled(enabled);
        playFromHere.setEnabled(enabled);
        if (value) {
            position.setText(R.string.preparing_audio);
        }
    }

    private void update() {
        if (destroyed || controller == null) {
            return;
        }
        if (currentIndex != controller.getCurrentMediaItemIndex()) {
            updateTrack();
        }
        QueueTrack item = current();
        if (item == null) {
            return;
        }
        long current = controller.getCurrentPosition();
        long chapterPosition = item.track.chapterOffsetMs + current;
        boolean playing = controller.isPlaying();
        play.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        play.setContentDescription(getString(playing ? R.string.pause : R.string.play));
        if (playing) {
            hidePlayFromHere();
        }
        updateText(false);
        position.setText(
                getString(
                        R.string.playback_position,
                        format(chapterPosition),
                        format(item.chapter.durationMs)));
        if (Math.abs(current - lastPersisted) >= 5000) {
            persistCurrent();
            lastPersisted = current;
        }
    }

    private void updateText(boolean force) {
        if (timeline == null || controller == null) {
            return;
        }
        int next = timeline.activeIndex(controller.getCurrentPosition());
        if (!force && next == activeSegment) {
            return;
        }
        activeSegment = next;
        fullText.setText(timeline.highlighted(next));
        if (controller.isPlaying()) {
            fullText.post(
                    () -> {
                        if (fullText.getLayout() == null) {
                            return;
                        }
                        int line =
                                fullText.getLayout()
                                        .getLineForOffset(timeline.activeStart(activeSegment));
                        int lineCenter =
                                fullText.getTop()
                                        + fullText.getCompoundPaddingTop()
                                        + (fullText.getLayout().getLineTop(line)
                                                        + fullText.getLayout().getLineBottom(line))
                                                / 2;
                        int max = Math.max(0, fullText.getHeight() - readerScroll.getHeight());
                        readerScroll.smoothScrollTo(
                                0,
                                Math.max(
                                        0,
                                        Math.min(max, lineCenter - readerScroll.getHeight() / 2)));
                    });
        }
    }

    private void persist(int index, long positionMs) {
        if (destroyed || index < 0 || index >= queue.size()) {
            return;
        }
        QueueTrack item = queue.get(index);
        db.saveProgress(
                bookId,
                item.chapter.id,
                item.track.id,
                positionMs,
                item.track.chapterOffsetMs + positionMs);
    }

    private void persistCurrent() {
        if (!destroyed && controller != null) {
            persist(controller.getCurrentMediaItemIndex(), controller.getCurrentPosition());
            lastPersisted = controller.getCurrentPosition();
        }
    }

    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private static float parseSpeed(String value) {
        try {
            return PlaybackSpeedRange.normalize(Float.parseFloat(value));
        } catch (RuntimeException ignored) {
            return 1f;
        }
    }

    private void renderPage(int page) {
        readerCrown.reset();
        if (audioControls != null) {
            audioControls.resetCrown();
        }
        indicator.setCurrentPage(page);
        indicator.setContentDescription(
                getString(
                        page == 0
                                ? R.string.controls_page_description
                                : R.string.text_page_description));
    }

    private boolean handleReaderCrown(MotionEvent event) {
        float delta = CrownMotionEvents.delta(event);
        if (delta == 0f) {
            return false;
        }
        int step = readerCrown.add(delta, event.getEventTime());
        if (step != 0) {
            readerScroll.smoothScrollBy(0, step * Math.max(1, fullText.getLineHeight()));
            if (controller != null && !controller.isPlaying()) {
                readerScroll.postDelayed(this::selectVisibleText, 200);
            }
        }
        return true;
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
        if (pager != null && pager.currentPage() == 1) {
            if (audioControls != null) {
                audioControls.resetCrown();
            }
            return handleReaderCrown(event) || super.dispatchGenericMotionEvent(event);
        }
        readerCrown.reset();
        return audioControls != null && audioControls.handleCrown(event)
                || super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            readerCrown.reset();
            if (audioControls != null) {
                audioControls.resetCrown();
            }
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
        mediaExecutor.shutdownNow();
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
