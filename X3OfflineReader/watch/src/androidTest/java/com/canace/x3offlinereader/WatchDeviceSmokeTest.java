package com.canace.x3offlinereader;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.music.MusicLibraryDb;
import com.canace.x3offlinereader.music.MusicModels;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.music.MusicLibraryActivity;
import com.canace.x3offlinereader.music.MusicScanner;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.importer.BookImportCoordinator;
import com.canace.x3offlinereader.ui.PlayerActivity;
import com.canace.x3offlinereader.ui.ChaptersActivity;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Framework-only smoke coverage that can run on the API 30 target without extra test libraries. */
public final class WatchDeviceSmokeTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private static final long TIMEOUT_MS=20000;

    public WatchDeviceSmokeTest(){super(MainActivity.class);}

    public void test01ImportAndRoundSafeHome() throws Exception {
        MainActivity activity=getActivity();
        assertInsideDisplay(activity.findViewById(R.id.books_button));
        assertInsideDisplay(activity.findViewById(R.id.music_button));
        assertEquals("小说",text(activity,R.id.books_button));assertEquals("音乐",text(activity,R.id.music_button));int[] booksAt=new int[2],musicAt=new int[2];activity.findViewById(R.id.books_button).getLocationOnScreen(booksAt);activity.findViewById(R.id.music_button).getLocationOnScreen(musicAt);assertEquals(booksAt[1],musicAt[1]);assertTrue(booksAt[0]<musicAt[0]);
        Activity library=getInstrumentation().startActivitySync(new Intent(activity,BookLibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));LibraryDb db=new LibraryDb(activity);
        try {
            assertInsideDisplay(library.findViewById(R.id.choose_button));assertTrue(library.findViewById(R.id.book_list).getHeight()>=180);assertTrue(library.findViewById(R.id.book_list).getWidth()>=360);ensureBook(library,db);
            List<LibraryModels.BookSummary> books=db.books();assertFalse(books.isEmpty());LibraryModels.BookSummary golden=goldenBook(db);
            List<LibraryModels.ChapterSummary> chapters=db.chapters(golden.id);assertEquals(3,chapters.size());
            for(LibraryModels.ChapterSummary chapter:chapters){
                assertTrue(new File(chapter.contentPath).isFile());
                assertFalse(chapter.tracks.isEmpty());for(LibraryModels.TrackSummary track:chapter.tracks){assertTrue(track.audioEntryPath.startsWith("audio/"));assertTrue(new File(track.timelinePath).isFile());}
            }
            waitFor(()->books.size()==((ListView)library.findViewById(R.id.book_list)).getCount(),TIMEOUT_MS);assertEquals(books.size(),((ListView)library.findViewById(R.id.book_list)).getCount());
        } finally {library.finish();db.close();}
    }

    public void test02BookPlaybackTimelineAndSettings() throws Exception {
        Context context=getInstrumentation().getTargetContext();LibraryDb db=new LibraryDb(context);
        Activity player=null;
        try {
            if(db.books().isEmpty()){Activity library=getInstrumentation().startActivitySync(new Intent(context,BookLibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));try{ensureBook(library,db);}finally{library.finish();}}
            LibraryModels.BookSummary book=goldenBook(db);List<LibraryModels.ChapterSummary> chapters=db.chapters(book.id);assertFalse(chapters.isEmpty());
            if(db.isFavorite(book.id))db.toggleFavorite(book.id);db.putSetting("playback_speed","1.25");db.saveProgress(book.id,chapters.get(0).id,0);
            Intent intent=new Intent(context,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("book_id",book.id).putExtra("track_id",chapters.get(0).id);
            player=getInstrumentation().startActivitySync(intent);final Activity active=player;
            waitFor(()->text(active,R.id.title).length()>0&&text(active,R.id.full_text).length()>0,TIMEOUT_MS);
            assertInsideDisplay(active.findViewById(R.id.back_button));assertInsideDisplay(active.findViewById(R.id.play_button));
            assertInsideDisplay(active.findViewById(R.id.forward_button));assertInsideDisplay(active.findViewById(R.id.previous_button));assertInsideDisplay(active.findViewById(R.id.next_button));assertInsideDisplay(active.findViewById(R.id.favorite_button));assertInsideDisplay(active.findViewById(R.id.speed_button));assertInsideDisplay(active.findViewById(R.id.volume_button));assertInsideDisplay(active.findViewById(R.id.output_button));assertTrue(text(active,R.id.volume_button).startsWith("音量 "));assertCompactOutput(active);
            assertTrue(text(active,R.id.full_text).length()>0);assertTrue(text(active,R.id.position).contains(" / "));
            assertEquals("1.25×",text(active,R.id.speed_button));getInstrumentation().runOnMainSync(()->{active.findViewById(R.id.favorite_button).performClick();active.findViewById(R.id.play_button).performClick();});
            Thread.sleep(1800);
            assertFalse(text(active,R.id.position).startsWith("00:00 /"));
            assertTrue(db.isFavorite(book.id));assertEquals("1.25",db.settingValue("playback_speed",""));
            getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);getInstrumentation().waitForIdleSync();player=null;
            assertTrue("Book playback must advance and persist",db.progress(book.id,chapters.get(0).id)>0);
        } finally {if(player!=null)player.finish();db.close();}
    }

    public void test03MusicScanPlaybackLyricsAndSettings() throws Exception {
        Context context=getInstrumentation().getTargetContext();MusicLibraryDb db=new MusicLibraryDb(context);Activity player=null;
        try {
            File dir=MusicScanner.musicDir(context);copyAsset("device-smoke.m4a",new File(dir,"device-smoke.m4a"));copyAsset("device-smoke.mp3",new File(dir,"device-smoke.mp3"));copyAsset("device-smoke.lrc",new File(dir,"device-smoke.lrc"));
            assertTrue(MusicScanner.scan(context,db)>=2);List<MusicModels.Track> tracks=db.tracks();assertTrue(tracks.size()>=2);Activity library=getInstrumentation().startActivitySync(new Intent(context,MusicLibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue(library.findViewById(R.id.music_list).getHeight()>=180);assertTrue(library.findViewById(R.id.music_list).getWidth()>=360);assertEquals("添加音频",text(library,R.id.add_music_button));assertInsideDisplay(library.findViewById(R.id.add_music_button));assertInsideDisplay(library.findViewById(R.id.scan_button));library.finish();
            MusicModels.Track track=findTrack(tracks,"device-smoke.m4a");assertTrue(track.durationMs>0);assertNotNull(track.lrcPath);
            if(track.favorite)db.toggleFavorite(track.id);db.putSetting("playback_speed","1.25");db.putSetting("playback_mode","repeat_all");db.saveProgress(track.id,0);
            Intent intent=new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("track_id",track.id);
            player=getInstrumentation().startActivitySync(intent);final Activity active=player;
            waitFor(()->active.findViewById(R.id.play_button).isEnabled()&&text(active,R.id.title).length()>0&&text(active,R.id.position).contains(" / "),TIMEOUT_MS);
            assertEquals("Selected music track must own the ready player",track.title,text(active,R.id.title));
            assertInsideDisplay(active.findViewById(R.id.previous_button));assertInsideDisplay(active.findViewById(R.id.play_button));assertInsideDisplay(active.findViewById(R.id.next_button));assertInsideDisplay(active.findViewById(R.id.favorite_button));assertInsideDisplay(active.findViewById(R.id.speed_button));assertInsideDisplay(active.findViewById(R.id.mode_button));assertInsideDisplay(active.findViewById(R.id.volume_button));assertInsideDisplay(active.findViewById(R.id.output_button));assertEqualWidth(active,R.id.previous_button,R.id.play_button,R.id.next_button,R.id.favorite_button,R.id.speed_button,R.id.mode_button,R.id.volume_button,R.id.output_button);assertTrue(text(active,R.id.volume_button).startsWith("音量 "));assertCompactOutput(active);
            assertEquals("1.25×",text(active,R.id.speed_button));assertEquals("列表循环",text(active,R.id.mode_button));getInstrumentation().runOnMainSync(()->{active.findViewById(R.id.favorite_button).performClick();active.findViewById(R.id.play_button).performClick();});
            waitFor("music favorite",()->findTrack(db.tracks(),"device-smoke.m4a").favorite,3000);
            waitFor("music lyric and playback progress",()->text(active,R.id.lyric).length()>0&&!text(active,R.id.position).startsWith("00:00 /"),10000);assertEquals("1.25",db.settingValue("playback_speed",""));assertEquals("repeat_all",db.settingValue("playback_mode",""));
            getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);getInstrumentation().waitForIdleSync();player=null;
            assertTrue("Music playback must advance and persist",db.progress(track.id)>0);
        } finally {if(player!=null)player.finish();db.close();}
    }

    public void test04Mp3SystemDecoderAndProgress() throws Exception {
        Context context=getInstrumentation().getTargetContext();MusicLibraryDb db=new MusicLibraryDb(context);Activity player=null;
        try {
            MusicModels.Track track=findTrack(db.tracks(),"device-smoke.mp3");db.saveProgress(track.id,0);
            Intent intent=new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("track_id",track.id);
            player=getInstrumentation().startActivitySync(intent);final Activity active=player;
            waitFor(()->active.findViewById(R.id.play_button).isEnabled()&&text(active,R.id.title).length()>0&&text(active,R.id.position).contains(" / "),TIMEOUT_MS);
            getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());Thread.sleep(1800);
            assertFalse(text(active,R.id.position).startsWith("00:00 /"));getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());
            getInstrumentation().runOnMainSync(active::finish);getInstrumentation().waitForIdleSync();player=null;assertTrue(db.progress(track.id)>0);
        } finally {if(player!=null)player.finish();db.close();}
    }

    public void test05RightSwipeExit() throws Exception {
        Context context=getInstrumentation().getTargetContext();LibraryDb db=new LibraryDb(context);Activity chapters=null;
        try {
            LibraryModels.BookSummary book=goldenBook(db);Intent intent=new Intent(context,ChaptersActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("book_id",book.id).putExtra("book_title",book.title);
            chapters=getInstrumentation().startActivitySync(intent);final Activity active=chapters;waitFor(()->active.findViewById(R.id.chapter_list).getWidth()>=360,3000);long time=SystemClock.uptimeMillis();
            getInstrumentation().runOnMainSync(()->{active.dispatchTouchEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,40,230,0));active.dispatchTouchEvent(MotionEvent.obtain(time,time+200,MotionEvent.ACTION_UP,180,230,0));});
            assertTrue(active.isFinishing());chapters=null;
        } finally {if(chapters!=null)chapters.finish();db.close();}
    }

    public void test06FiveTimesBookPlayback() throws Exception {
        Context context=getInstrumentation().getTargetContext();LibraryDb db=new LibraryDb(context);Activity player=null;
        try {
            LibraryModels.BookSummary book=goldenBook(db);LibraryModels.ChapterSummary chapter=db.chapters(book.id).get(0);db.putSetting("playback_speed","5.0");db.saveProgress(book.id,chapter.id,0);
            Intent intent=new Intent(context,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("book_id",book.id).putExtra("track_id",chapter.id);player=getInstrumentation().startActivitySync(intent);final Activity active=player;waitFor(()->active.findViewById(R.id.play_button).isEnabled()&&text(active,R.id.position).contains(" / "),TIMEOUT_MS);assertEquals("5×",text(active,R.id.speed_button));
            getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());waitFor(()->!text(active,R.id.position).startsWith("00:00 /"),3000);getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());assertTrue(db.progress(book.id,chapter.id)>0);
        } finally {db.putSetting("playback_speed","1.25");if(player!=null)player.finish();db.close();}
    }

    public void test07FixedOpaqueChoiceHeader() throws Exception {
        MainActivity activity=getActivity();String[] labels=new String[18];for(int i=0;i<labels.length;i++)labels[i]=(i*100/(labels.length-1))+"%";Dialog[] holder=new Dialog[1];getInstrumentation().runOnMainSync(()->holder[0]=RoundChoiceDialog.show(activity,"媒体音量",labels,6,which->{}));getInstrumentation().waitForIdleSync();TextView header=holder[0].findViewById(R.id.choice_dialog_title);ListView list=holder[0].findViewById(R.id.choice_dialog_list);assertNotNull(header);assertNotNull(list);assertEquals("媒体音量",header.getText().toString());assertTrue(header.getBackground()!=null);assertTrue(header.getBottom()<=list.getTop());int top=header.getTop();getInstrumentation().runOnMainSync(()->list.setSelection(labels.length-1));Thread.sleep(300);assertEquals(top,header.getTop());assertEquals("媒体音量",header.getText().toString());getInstrumentation().runOnMainSync(holder[0]::dismiss);
    }

    public void test08MusicShuffleMode() throws Exception {
        Context context=getInstrumentation().getTargetContext();MusicLibraryDb db=new MusicLibraryDb(context);Activity player=null;com.google.common.util.concurrent.ListenableFuture<MediaController> future=null;
        try {
            MusicModels.Track track=findTrack(db.tracks(),"device-smoke.m4a");db.putSetting("playback_mode","shuffle");
            player=getInstrumentation().startActivitySync(new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("track_id",track.id));final Activity active=player;
            waitFor(()->"列表随机".equals(text(active,R.id.mode_button)),TIMEOUT_MS);
            AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>> pending=new AtomicReference<>();getInstrumentation().runOnMainSync(()->pending.set(new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PlaybackService.class))).buildAsync()));future=pending.get();MediaController session=future.get(10,TimeUnit.SECONDS);
            AtomicReference<Boolean> shuffle=new AtomicReference<>();AtomicReference<Integer> repeat=new AtomicReference<>();getInstrumentation().runOnMainSync(()->{shuffle.set(session.getShuffleModeEnabled());repeat.set(session.getRepeatMode());});assertTrue(shuffle.get());assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL,(int)repeat.get());
        } finally {if(future!=null){com.google.common.util.concurrent.ListenableFuture<MediaController> release=future;getInstrumentation().runOnMainSync(()->MediaController.releaseFuture(release));}db.putSetting("playback_mode","repeat_all");if(player!=null)player.finish();db.close();}
    }

    private static String text(Activity activity,int id){CharSequence value=((TextView)activity.findViewById(id)).getText();return value==null?"":value.toString();}
    private static void assertCompactOutput(Activity activity){String label=text(activity,R.id.output_button);assertTrue("扬声器".equals(label)||"蓝牙".equals(label));}
    private static MusicModels.Track findTrack(List<MusicModels.Track> tracks,String suffix){for(MusicModels.Track track:tracks)if(track.path.endsWith(suffix))return track;fail("Missing music fixture: "+suffix);return null;}
    private void ensureBook(Activity activity,LibraryDb db) throws Exception {for(LibraryModels.BookSummary book:db.books())if("x3-golden-sample-ebfa1632c722".equals(book.id))return;File target=new File(activity.getExternalFilesDir("test-packages"),"golden-two-volume.opbook");copyAsset("golden-two-volume.opbook",target);assertTrue(BookImportCoordinator.get().start(activity,java.util.Collections.singletonList(new BookImportCoordinator.Source(Uri.fromFile(target),target.getName()))));waitFor(()->{for(LibraryModels.BookSummary book:db.books())if("x3-golden-sample-ebfa1632c722".equals(book.id))return true;return false;},TIMEOUT_MS);}
    private static LibraryModels.BookSummary goldenBook(LibraryDb db){for(LibraryModels.BookSummary book:db.books())if("x3-golden-sample-ebfa1632c722".equals(book.id))return book;fail("Missing golden book");return null;}
    private void copyAsset(String name,File target) throws Exception {File parent=target.getParentFile();assertTrue(parent.mkdirs()||parent.isDirectory());try(InputStream in=getInstrumentation().getContext().getAssets().open(name);FileOutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)if(n>0)out.write(buffer,0,n);}}
    private static void assertInsideDisplay(View view){int[] at=new int[2];view.getLocationOnScreen(at);assertTrue(view.getVisibility()==View.VISIBLE);assertTrue(at[0]>=0&&at[1]>=0);assertTrue(at[0]+view.getWidth()<=466);assertTrue(at[1]+view.getHeight()<=466);}
    private static void assertEqualWidth(Activity activity,int... ids){int width=activity.findViewById(ids[0]).getWidth();assertTrue(width>0);for(int id:ids)assertTrue(Math.abs(width-activity.findViewById(id).getWidth())<=1);}
    private void waitFor(Check check,long timeout) throws Exception {waitFor("device state",check,timeout);}
    private void waitFor(String label,Check check,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(check.ok())return;Thread.sleep(100);}fail("Timed out waiting for "+label);}
    private interface Check {boolean ok() throws Exception;}
}
