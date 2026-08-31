package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.music.MusicScanner;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Home/background keeps the player alive; launcher and MediaSession entries return to that page. */
public final class WatchPlayerFocusLifecycleProbeTest extends InstrumentationTestCase {
    public void testDesktopAndMediaShortcutsReturnToPlayerUntilRightSwipeStops() throws Exception {
        Context context=getInstrumentation().getTargetContext();MusicLibraryDb db=new MusicLibraryDb(context);Activity player=null;ListenableFuture<MediaController> future=null;MediaController observer=null;
        try {
            File file=new File(MusicScanner.musicDir(context),"device-background.m4a");copyAsset("device-background.m4a",file);assertTrue(MusicScanner.scan(context,db)>0);MusicModels.Track selected=null;for(MusicModels.Track track:db.tracks())if(track.path.endsWith("device-background.m4a"))selected=track;assertNotNull(selected);db.putSetting("playback_speed","1.0");db.putSetting("playback_mode","repeat_one");db.saveProgress(selected.id,0);Intent intent=new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("track_id",selected.id);player=getInstrumentation().startActivitySync(intent);final Activity active=player;waitFor(()->active.findViewById(R.id.play_button).isEnabled()&&text(active,R.id.position).contains(" / "),20000);AtomicReference<ListenableFuture<MediaController>> pending=new AtomicReference<>();getInstrumentation().runOnMainSync(()->pending.set(new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PlaybackService.class))).buildAsync()));future=pending.get();observer=future.get(10,TimeUnit.SECONDS);final MediaController session=observer;getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());waitFor(()->onMain(session::isPlaying),8000);PendingIntent shortcut=onMain(session::getSessionActivity);assertNotNull(shortcut);getInstrumentation().runOnMainSync(()->active.moveTaskToBack(true));Thread.sleep(1500);assertFalse(active.isFinishing());assertTrue("Home/background must keep playing",onMain(session::isPlaying));Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(context,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(launcher);waitFor(()->onMain(active::hasWindowFocus),5000);assertTrue("Desktop launcher must return to the player",onMain(active::hasWindowFocus));assertTrue(onMain(session::isPlaying));getInstrumentation().runOnMainSync(()->active.moveTaskToBack(true));Thread.sleep(500);shortcut.send();waitFor(()->onMain(active::hasWindowFocus),5000);assertTrue("System media shortcut must return to the player",onMain(active::hasWindowFocus));assertTrue(onMain(session::isPlaying));long time=SystemClock.uptimeMillis();getInstrumentation().runOnMainSync(()->{active.dispatchTouchEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,40,230,0));active.dispatchTouchEvent(MotionEvent.obtain(time,time+200,MotionEvent.ACTION_UP,180,230,0));});waitFor(()->onMain(()->!session.isPlaying()&&session.getMediaItemCount()==0),5000);assertTrue(active.isFinishing());player=null;
        } finally {if(player!=null){player.finish();getInstrumentation().waitForIdleSync();}if(future!=null){ListenableFuture<MediaController> release=future;getInstrumentation().runOnMainSync(()->MediaController.releaseFuture(release));}db.putSetting("playback_speed","1.25");db.putSetting("playback_mode","repeat_all");File fixture=new File(MusicScanner.musicDir(context),"device-background.m4a");if(fixture.isFile())assertTrue(fixture.delete());MusicScanner.scan(context,db);db.close();}
    }
    private void copyAsset(String name,File target) throws Exception {try(InputStream in=getInstrumentation().getContext().getAssets().open(name);FileOutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)if(n>0)out.write(buffer,0,n);}}
    private static String text(Activity activity,int id){CharSequence value=((TextView)activity.findViewById(id)).getText();return value==null?"":value.toString();}
    private void waitFor(Check check,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(check.ok())return;Thread.sleep(100);}fail("Timed out waiting for focus lifecycle state");}
    private <T> T onMain(Value<T> source){AtomicReference<T> value=new AtomicReference<>();getInstrumentation().runOnMainSync(()->value.set(source.get()));return value.get();}
    private interface Check {boolean ok() throws Exception;}
    private interface Value<T> {T get();}
}
