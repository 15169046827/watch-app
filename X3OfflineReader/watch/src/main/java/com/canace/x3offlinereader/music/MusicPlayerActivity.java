package com.canace.x3offlinereader.music;

import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.AudioOutputControls;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.canace.x3offlinereader.ui.SwipeBackActivity;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MusicPlayerActivity extends SwipeBackActivity {
    private static final float[] SPEEDS={0.8f,1f,1.25f,1.5f,2f,2.5f,3f,5f};
    private static final String[] SPEED_LABELS={"0.8×","1×","1.25×","1.5×","2×","2.5×","3×","5×"};
    private static final String[] MODE_VALUES={"stop_after_track","repeat_one","repeat_all","shuffle"};
    private static final int[] MODE_LABELS={R.string.mode_stop_after_track,R.string.mode_repeat_one,R.string.mode_repeat_all,R.string.mode_shuffle};
    private final Handler handler=new Handler(Looper.getMainLooper());private final Runnable ticker=new Runnable(){@Override public void run(){update();handler.postDelayed(this,250);}};
    private MusicLibraryDb db;private List<MusicModels.Track> tracks;private ListenableFuture<MediaController> future;private MediaController controller;private TextView title,lyric,position;private Button play,favorite,speedButton,modeButton;private LrcReader lrc=new LrcReader(null);private int lastIndex=-1;private long lastPersisted;private float speed;private String playbackMode;private AudioOutputControls audioControls;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_music_player);enableSwipeBack(R.id.root);db=new MusicLibraryDb(this);tracks=db.tracks();long wanted=getIntent().getLongExtra("track_id",-1);int selected=0;for(int i=0;i<tracks.size();i++)if(tracks.get(i).id==wanted)selected=i;title=findViewById(R.id.title);lyric=findViewById(R.id.lyric);position=findViewById(R.id.position);play=findViewById(R.id.play_button);favorite=findViewById(R.id.favorite_button);speedButton=findViewById(R.id.speed_button);modeButton=findViewById(R.id.mode_button);audioControls=new AudioOutputControls(this,findViewById(R.id.volume_button),findViewById(R.id.output_button));speed=parseSpeed(db.settingValue("playback_speed","1.0"));playbackMode=parseMode(db.settingValue("playback_mode",MODE_VALUES[0]));refreshSpeed();refreshMode();play.setOnClickListener(v->{if(controller==null)return;if(controller.isPlaying())controller.pause();else controller.play();});findViewById(R.id.previous_button).setOnClickListener(v->{if(controller!=null)controller.seekToPreviousMediaItem();});findViewById(R.id.next_button).setOnClickListener(v->{if(controller!=null)controller.seekToNextMediaItem();});favorite.setOnClickListener(v->{MusicModels.Track track=current();if(track!=null){db.toggleFavorite(track.id);tracks=db.tracks();updateTrack();}});speedButton.setOnClickListener(v->showSpeedPicker());modeButton.setOnClickListener(v->showModePicker());
        if(tracks.isEmpty()){Toast.makeText(this,R.string.no_tracks,Toast.LENGTH_LONG).show();finish();return;}
        PlaybackTargetStore.music(this,tracks.get(selected).id);
        int start=selected;future=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).buildAsync();future.addListener(()->{try{controller=future.get();controller.addListener(new Player.Listener(){@Override public void onIsPlayingChanged(boolean playing){if(!playing)persistCurrent();}@Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){if(oldPosition.mediaItemIndex>=0&&oldPosition.mediaItemIndex<tracks.size())db.saveProgress(tracks.get(oldPosition.mediaItemIndex).id,oldPosition.positionMs);}@Override public void onMediaItemTransition(MediaItem item,int reason){if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO&&MODE_VALUES[0].equals(playbackMode))controller.pause();}});controller.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);controller.setPlaybackSpeed(speed);applyPlaybackMode();List<MediaItem> items=new ArrayList<>();for(MusicModels.Track track:tracks)items.add(MediaItem.fromUri(Uri.fromFile(new File(track.path))));controller.setMediaItems(items,start,db.progress(tracks.get(start).id));controller.prepare();handler.post(ticker);}catch(Exception error){runOnUiThread(()->Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show());}},MoreExecutors.directExecutor());
    }
    private MusicModels.Track current(){if(controller==null||controller.getCurrentMediaItemIndex()<0||controller.getCurrentMediaItemIndex()>=tracks.size())return null;return tracks.get(controller.getCurrentMediaItemIndex());}
    private void updateTrack(){MusicModels.Track track=current();if(track==null)return;PlaybackTargetStore.music(this,track.id);title.setText(track.title);favorite.setText(track.favorite?R.string.unfavorite:R.string.favorite);lrc=new LrcReader(track.lrcPath);lastPersisted=controller.getCurrentPosition();}
    private void refreshSpeed(){speedButton.setText(SPEED_LABELS[speedIndex()]);}
    private int speedIndex(){int nearest=1;float difference=Float.MAX_VALUE;for(int i=0;i<SPEEDS.length;i++){float current=Math.abs(SPEEDS[i]-speed);if(current<difference){difference=current;nearest=i;}}return nearest;}
    private void showSpeedPicker(){RoundChoiceDialog.show(this,getText(R.string.speed_title),SPEED_LABELS,speedIndex(),which->{speed=SPEEDS[which];db.putSetting("playback_speed",Float.toString(speed));if(controller!=null)controller.setPlaybackSpeed(speed);refreshSpeed();});}
    private void refreshMode(){modeButton.setText(MODE_LABELS[modeIndex()]);}
    private int modeIndex(){for(int i=0;i<MODE_VALUES.length;i++)if(MODE_VALUES[i].equals(playbackMode))return i;return 0;}
    private void showModePicker(){String[] labels=new String[MODE_LABELS.length];for(int i=0;i<labels.length;i++)labels[i]=getString(MODE_LABELS[i]);RoundChoiceDialog.show(this,getText(R.string.mode_title),labels,modeIndex(),which->{playbackMode=MODE_VALUES[which];db.putSetting("playback_mode",playbackMode);applyPlaybackMode();refreshMode();});}
    private void applyPlaybackMode(){if(controller!=null){boolean shuffle=MODE_VALUES[3].equals(playbackMode);controller.setShuffleModeEnabled(shuffle);controller.setRepeatMode(MODE_VALUES[1].equals(playbackMode)?Player.REPEAT_MODE_ONE:MODE_VALUES[2].equals(playbackMode)||shuffle?Player.REPEAT_MODE_ALL:Player.REPEAT_MODE_OFF);}}
    private void update(){if(controller==null)return;if(lastIndex!=controller.getCurrentMediaItemIndex()){lastIndex=controller.getCurrentMediaItemIndex();updateTrack();}long ms=controller.getCurrentPosition();play.setText(controller.isPlaying()?R.string.pause:R.string.play);lyric.setText(lrc.at(ms));position.setText(getString(R.string.playback_position,format(ms),format(Math.max(0,controller.getDuration()))));if(Math.abs(ms-lastPersisted)>=5000){MusicModels.Track track=current();if(track!=null)db.saveProgress(track.id,ms);lastPersisted=ms;}}
    private void persistCurrent(){MusicModels.Track track=current();if(controller!=null&&track!=null){db.saveProgress(track.id,controller.getCurrentPosition());lastPersisted=controller.getCurrentPosition();}}
    private static String format(long ms){long s=ms/1000;return String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);}
    private static float parseSpeed(String value){try{float parsed=Float.parseFloat(value);return parsed>=0.5f&&parsed<=5f?parsed:1f;}catch(RuntimeException ignored){return 1f;}}
    private static String parseMode(String value){for(String candidate:MODE_VALUES)if(candidate.equals(value))return value;return MODE_VALUES[0];}
    @Override protected void onStart(){super.onStart();audioControls.start();}
    @Override protected void onStop(){audioControls.stop();super.onStop();}
    @Override protected void onPause(){persistCurrent();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(!isChangingConfigurations()){if(controller!=null){controller.stop();controller.clearMediaItems();}PlaybackTargetStore.clear(this);}if(future!=null)MediaController.releaseFuture(future);db.close();super.onDestroy();}
}
