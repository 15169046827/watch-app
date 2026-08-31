package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.music.MusicScanner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Holds a 30-second AAC fixture for concurrent screen-off, media-key and MediaSession probes. */
public final class WatchScreenOffPlaybackProbeTest extends InstrumentationTestCase {
    public void testLongPlaybackSurvivesScreenOffAndSystemControls() throws Exception {
        Context context=getInstrumentation().getTargetContext();MusicLibraryDb db=new MusicLibraryDb(context);Activity player=null;String oldSpeed=db.settingValue("playback_speed","1.0");
        try {
            File target=new File(MusicScanner.musicDir(context),"device-background.m4a");copyAsset("device-background.m4a",target);assertTrue(MusicScanner.scan(context,db)>0);MusicModels.Track track=null;for(MusicModels.Track item:db.tracks())if(item.path.endsWith("device-background.m4a"))track=item;assertNotNull(track);final MusicModels.Track selected=track;db.putSetting("playback_speed","1.0");db.saveProgress(selected.id,0);
            Intent intent=new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("track_id",selected.id);player=getInstrumentation().startActivitySync(intent);final Activity active=player;waitFor(()->active.findViewById(R.id.play_button).isEnabled()&&text(active,R.id.position).contains(" / "),20000);
            getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());Thread.sleep(25000);assertFalse(text(active,R.id.position).startsWith("00:00 /"));getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());assertTrue(db.progress(selected.id)>0);
        } finally {if(player!=null)player.finish();db.putSetting("playback_speed",oldSpeed);File fixture=new File(MusicScanner.musicDir(context),"device-background.m4a");if(fixture.isFile())assertTrue(fixture.delete());MusicScanner.scan(context,db);db.close();}
    }
    private void copyAsset(String name,File target) throws Exception {try(InputStream in=getInstrumentation().getContext().getAssets().open(name);FileOutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)if(n>0)out.write(buffer,0,n);}}
    private static String text(Activity activity,int id){CharSequence value=((TextView)activity.findViewById(id)).getText();return value==null?"":value.toString();}
    private void waitFor(Check check,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(check.ok())return;Thread.sleep(100);}fail("Timed out waiting for playback UI");}
    private interface Check {boolean ok();}
}
