package com.canace.x3offlinereader.ui;

import android.content.ComponentName;
import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.Button;
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
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends SwipeBackActivity {
    private static final float[] SPEEDS={0.8f,1f,1.25f,1.5f,2f,2.5f,3f,5f};
    private static final String[] SPEED_LABELS={"0.8×","1×","1.25×","1.5×","2×","2.5×","3×","5×"};
    private static final class QueueTrack {final int chapterIndex;final LibraryModels.ChapterSummary chapter;final LibraryModels.TrackSummary track;QueueTrack(int chapterIndex,LibraryModels.ChapterSummary chapter,LibraryModels.TrackSummary track){this.chapterIndex=chapterIndex;this.chapter=chapter;this.track=track;}}
    private final Handler handler=new Handler(Looper.getMainLooper());private final Runnable ticker=new Runnable(){@Override public void run(){update();handler.postDelayed(this,250);}};
    private ListenableFuture<MediaController> future;private MediaController controller;private TimelineReader timeline;private LibraryDb db;private final List<LibraryModels.ChapterSummary> chapters=new ArrayList<>();private final List<QueueTrack> queue=new ArrayList<>();
    private final ExecutorService mediaExecutor=Executors.newSingleThreadExecutor();private volatile boolean preparing,prefetching,controllerReady,destroyed;
    private String bookId,targetOwner;private TextView title,position,fullText;private ScrollView readerScroll;private Button play,favorite,speedButton,previous,next,back,forward;private int currentIndex=-1,activeSegment=-1;private long lastPersisted;private float speed;private AudioOutputControls audioControls;

    @SuppressLint("ClickableViewAccessibility") @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_player);enableSwipeBack(R.id.root);bookId=getIntent().getStringExtra("book_id");String wantedChapter=getIntent().getStringExtra("chapter_id");if(wantedChapter==null)wantedChapter=getIntent().getStringExtra("track_id");
        db=new LibraryDb(this);chapters.addAll(db.chapters(bookId));for(int ci=0;ci<chapters.size();ci++)for(LibraryModels.TrackSummary track:chapters.get(ci).tracks)queue.add(new QueueTrack(ci,chapters.get(ci),track));
        title=findViewById(R.id.title);position=findViewById(R.id.position);fullText=findViewById(R.id.full_text);readerScroll=findViewById(R.id.reader_scroll);play=findViewById(R.id.play_button);favorite=findViewById(R.id.favorite_button);speedButton=findViewById(R.id.speed_button);audioControls=new AudioOutputControls(this,findViewById(R.id.volume_button),findViewById(R.id.output_button));speed=parseSpeed(db.settingValue("playback_speed","1.0"));refreshFavorite();refreshSpeed();
        previous=findViewById(R.id.previous_button);next=findViewById(R.id.next_button);back=findViewById(R.id.back_button);forward=findViewById(R.id.forward_button);setPreparing(true);play.setOnClickListener(v->{if(controller==null||preparing)return;if(controller.isPlaying())controller.pause();else{controller.play();updateText(true);}update();});back.setOnClickListener(v->seek(-15000));forward.setOnClickListener(v->seek(15000));previous.setOnClickListener(v->seekChapter(-1));next.setOnClickListener(v->seekChapter(1));favorite.setOnClickListener(v->{db.toggleFavorite(bookId);refreshFavorite();});speedButton.setOnClickListener(v->showSpeedPicker());readerScroll.setOnTouchListener((v,event)->controller!=null&&controller.isPlaying()&&(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE));
        if(queue.isEmpty()){Toast.makeText(this,R.string.no_tracks,Toast.LENGTH_LONG).show();finish();return;}
        LibraryModels.ResumePoint resume=getIntent().getBooleanExtra("resume_latest",false)?db.latestProgress(bookId):wantedChapter==null?db.latestProgress(bookId):db.chapterProgress(bookId,wantedChapter);int start=findStart(wantedChapter,resume);long startPosition=resume!=null&&queue.get(start).track.id.equals(resume.trackId)?resume.trackPositionMs:0;
        targetOwner=PlaybackTargetStore.beginBook(this,bookId,queue.get(start).track.id);int selected=start;prepareWindow(queue.get(start).chapterIndex,true,()->initController(selected,startPosition));
    }
    private void initController(int selected,long startPosition){future=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).buildAsync();future.addListener(()->{if(destroyed)return;try{controller=future.get();if(destroyed)return;controller.pause();controller.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),true);controller.setPlaybackSpeed(speed);controller.setRepeatMode(Player.REPEAT_MODE_OFF);controller.setShuffleModeEnabled(false);List<MediaItem> items=new ArrayList<>();for(QueueTrack item:queue)items.add(new MediaItem.Builder().setMediaId(mediaId(item)).setUri(Uri.fromFile(new File(item.track.audioPath))).build());controller.setMediaItems(items,selected,startPosition);controller.addListener(new Player.Listener(){@Override public void onIsPlayingChanged(boolean playing){if(!playing)persistCurrent();}@Override public void onPlaybackStateChanged(int state){maybeReady(selected);}@Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){persist(oldPosition.mediaItemIndex,oldPosition.positionMs);}@Override public void onMediaItemTransition(MediaItem item,int reason){updateTrack();}@Override public void onTimelineChanged(androidx.media3.common.Timeline timeline,int reason){maybeReady(selected);}});controller.prepare();handler.post(ticker);}catch(Exception error){if(!destroyed)runOnUiThread(()->{if(!destroyed)Toast.makeText(this,R.string.no_tracks,Toast.LENGTH_LONG).show();});}},MoreExecutors.directExecutor());}
    private void maybeReady(int selected){if(!destroyed&&readyQueue(selected)){controllerReady=true;setPreparing(false);}}
    private boolean readyQueue(int selected){MediaItem current=controller.getCurrentMediaItem();return controller.getPlaybackState()!=Player.STATE_IDLE&&controller.getMediaItemCount()==queue.size()&&controller.getCurrentMediaItemIndex()==selected&&current!=null&&mediaId(queue.get(selected)).equals(current.mediaId);}
    private String mediaId(QueueTrack item){return "book:"+bookId+":"+item.track.id;}
    private int findStart(String wanted,LibraryModels.ResumePoint resume){String track=resume==null?null:resume.trackId;for(int i=0;i<queue.size();i++)if(track!=null&&track.equals(queue.get(i).track.id))return i;for(int i=0;i<queue.size();i++)if(wanted!=null&&(wanted.equals(queue.get(i).chapter.id)||wanted.equals(queue.get(i).track.id)))return i;return 0;}
    private void refreshFavorite(){favorite.setText(db.isFavorite(bookId)?R.string.unfavorite:R.string.favorite);}
    private void refreshSpeed(){speedButton.setText(SPEED_LABELS[speedIndex()]);}
    private int speedIndex(){int nearest=1;float difference=Float.MAX_VALUE;for(int i=0;i<SPEEDS.length;i++){float current=Math.abs(SPEEDS[i]-speed);if(current<difference){difference=current;nearest=i;}}return nearest;}
    private void showSpeedPicker(){RoundChoiceDialog.show(this,getText(R.string.speed_title),SPEED_LABELS,speedIndex(),which->{speed=SPEEDS[which];db.putSetting("playback_speed",Float.toString(speed));if(controller!=null)controller.setPlaybackSpeed(speed);refreshSpeed();});}
    private QueueTrack current(){return currentIndex>=0&&currentIndex<queue.size()?queue.get(currentIndex):null;}
    private void updateTrack(){if(destroyed||controller==null)return;currentIndex=controller.getCurrentMediaItemIndex();QueueTrack item=current();if(item==null)return;PlaybackTargetStore.book(this,bookId,item.track.id,targetOwner);try{timeline=new TimelineReader(item.track.timelinePath,item.chapter.contentPath);title.setText(item.chapter.title);activeSegment=-1;fullText.setText(timeline.fullText);lastPersisted=controller.getCurrentPosition();updateText(true);prepareWindow(item.chapterIndex,false,null);}catch(Exception error){if(!destroyed)Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}
    private void seek(long delta){if(controller!=null)controller.seekTo(Math.max(0,Math.min(controller.getDuration()>0?controller.getDuration():Long.MAX_VALUE,controller.getCurrentPosition()+delta)));}
    private void seekChapter(int delta){QueueTrack item=current();if(controller==null||item==null||preparing)return;int target=Math.max(0,Math.min(chapters.size()-1,item.chapterIndex+delta));if(target==item.chapterIndex){if(delta<0)controller.seekTo(0);return;}prepareWindow(target,true,()->{for(int i=0;i<queue.size();i++)if(queue.get(i).chapterIndex==target){LibraryModels.ResumePoint saved=db.chapterProgress(bookId,queue.get(i).chapter.id);int index=saved==null?i:findStart(queue.get(i).chapter.id,saved);controller.seekTo(index,saved==null?0:saved.trackPositionMs);return;}});}
    private List<LibraryModels.TrackSummary> window(int chapterIndex){List<LibraryModels.TrackSummary> result=new ArrayList<>();for(int i=Math.max(0,chapterIndex-1);i<=Math.min(chapters.size()-1,chapterIndex+1);i++)result.addAll(chapters.get(i).tracks);return result;}
    private void prepareWindow(int chapterIndex,boolean blocking,Runnable complete){if(destroyed||mediaExecutor.isShutdown()||preparing||(!blocking&&prefetching))return;LibraryModels.BookStorage storage=db.storage(bookId);if(storage==null)return;List<LibraryModels.TrackSummary> tracks=window(chapterIndex);boolean indexed=storage.indexedPackage();if(!indexed){if(complete!=null&&!destroyed)complete.run();return;}if(blocking){preparing=true;setPreparing(true);}else prefetching=true;boolean accepted=LifecycleExecutor.execute(mediaExecutor,()->{Exception failure=null;try{if(!BookSourceAccess.exists(this,storage.sourceUri))throw new BookSourceAccess.SourceMissingException();BookMediaCache.prepare(this,bookId,storage.sourceUri,storage.rootPath,tracks);BookMediaCache.retain(this,bookId,tracks);}catch(Exception error){failure=error;}Exception result=failure;runOnUiThread(()->{if(destroyed)return;if(blocking){preparing=false;setPreparing(false);}else prefetching=false;if(result!=null){Toast.makeText(this,result.getMessage(),Toast.LENGTH_LONG).show();if(controller==null)finish();}else if(complete!=null)complete.run();});});if(!accepted&&!destroyed){if(blocking){preparing=false;setPreparing(false);}else prefetching=false;}}
    private void setPreparing(boolean value){boolean enabled=!value&&controllerReady;play.setEnabled(enabled);previous.setEnabled(enabled);next.setEnabled(enabled);back.setEnabled(enabled);forward.setEnabled(enabled);favorite.setEnabled(enabled);speedButton.setEnabled(enabled);if(value)position.setText(R.string.preparing_audio);}
    private void update(){if(destroyed||controller==null)return;if(currentIndex!=controller.getCurrentMediaItemIndex())updateTrack();QueueTrack item=current();if(item==null)return;long current=controller.getCurrentPosition(),chapterPosition=item.track.chapterOffsetMs+current;play.setText(controller.isPlaying()?R.string.pause:R.string.play);updateText(false);position.setText(getString(R.string.playback_position,format(chapterPosition),format(item.chapter.durationMs)));if(Math.abs(current-lastPersisted)>=5000){persistCurrent();lastPersisted=current;}}
    private void updateText(boolean force){if(timeline==null||controller==null)return;int next=timeline.activeIndex(controller.getCurrentPosition());if(!force&&next==activeSegment)return;activeSegment=next;fullText.setText(timeline.highlighted(next));if(controller.isPlaying())fullText.post(()->{if(fullText.getLayout()==null)return;int line=fullText.getLayout().getLineForOffset(timeline.activeStart(activeSegment));readerScroll.smoothScrollTo(0,Math.max(0,fullText.getLayout().getLineTop(line)-readerScroll.getHeight()/3));});}
    private void persist(int index,long positionMs){if(destroyed||index<0||index>=queue.size())return;QueueTrack item=queue.get(index);db.saveProgress(bookId,item.chapter.id,item.track.id,positionMs,item.track.chapterOffsetMs+positionMs);}
    private void persistCurrent(){if(!destroyed&&controller!=null){persist(controller.getCurrentMediaItemIndex(),controller.getCurrentPosition());lastPersisted=controller.getCurrentPosition();}}
    private static String format(long ms){long total=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%02d:%02d",total/60,total%60);}
    private static float parseSpeed(String value){try{float parsed=Float.parseFloat(value);return parsed>=0.5f&&parsed<=5f?parsed:1f;}catch(RuntimeException ignored){return 1f;}}
    @Override protected void onStart(){super.onStart();audioControls.start();}
    @Override protected void onStop(){audioControls.stop();super.onStop();}
    @Override protected void onPause(){persistCurrent();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);mediaExecutor.shutdownNow();if(!isChangingConfigurations()&&PlaybackTargetStore.clearIfOwner(this,targetOwner)&&controller!=null){controller.stop();controller.clearMediaItems();}if(future!=null)MediaController.releaseFuture(future);db.close();super.onDestroy();}
}
