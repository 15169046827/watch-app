package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.ui.PlayerActivity;
import java.util.List;

/** Holds real AAC playback long enough for shell-side lock-screen and MediaSession probes. */
public final class WatchBackgroundPlaybackProbeTest extends InstrumentationTestCase {
    public void testBackgroundPlaybackAndAutomaticTrackTransition() throws Exception {
        Context context=getInstrumentation().getTargetContext();LibraryDb db=new LibraryDb(context);Activity player=null;
        try {
            LibraryModels.BookSummary book=goldenBook(db);List<LibraryModels.ChapterSummary> chapters=db.chapters(book.id);assertTrue(chapters.size()>1);db.putSetting("playback_speed","1.0");db.saveProgress(book.id,chapters.get(0).id,0);
            Intent intent=new Intent(context,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("book_id",book.id).putExtra("track_id",chapters.get(0).id);
            player=getInstrumentation().startActivitySync(intent);final Activity active=player;waitFor(()->text(active,R.id.title).length()>0&&text(active,R.id.position).contains(" / "),20000);String first=text(active,R.id.title);
            getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());Thread.sleep(15000);
            assertFalse(first.equals(text(active,R.id.title)));getInstrumentation().runOnMainSync(()->active.findViewById(R.id.play_button).performClick());
            boolean saved=false;for(LibraryModels.ChapterSummary chapter:chapters)saved|=db.progress(book.id,chapter.id)>0;assertTrue(saved);
        } finally {db.putSetting("playback_speed","1.25");if(player!=null)player.finish();db.close();}
    }
    private static String text(Activity activity,int id){CharSequence value=((TextView)activity.findViewById(id)).getText();return value==null?"":value.toString();}
    private static LibraryModels.BookSummary goldenBook(LibraryDb db){for(LibraryModels.BookSummary book:db.books())if("x3-golden-sample-ebfa1632c722".equals(book.id))return book;fail("Missing golden book");return null;}
    private void waitFor(Check check,long timeout) throws Exception {long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(check.ok())return;Thread.sleep(100);}fail("Timed out waiting for playback UI");}
    private interface Check {boolean ok();}
}
