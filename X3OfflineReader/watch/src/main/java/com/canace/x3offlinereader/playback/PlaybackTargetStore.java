package com.canace.x3offlinereader.playback;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.canace.x3offlinereader.MainActivity;
import com.canace.x3offlinereader.music.MusicPlayerActivity;
import com.canace.x3offlinereader.ui.PlayerActivity;

/** Small non-sensitive pointer used by the system MediaSession button to reopen the live player page. */
public final class PlaybackTargetStore {
    private static final String FILE="playback_target",KIND="kind",BOOK="book_id",TRACK="track_id";private PlaybackTargetStore(){}
    public static void book(Context context,String bookId,String trackId){context.getSharedPreferences(FILE,0).edit().putString(KIND,"book").putString(BOOK,bookId).putString(TRACK,trackId).apply();}
    public static void music(Context context,long trackId){context.getSharedPreferences(FILE,0).edit().putString(KIND,"music").remove(BOOK).putString(TRACK,Long.toString(trackId)).apply();}
    public static void clear(Context context){context.getSharedPreferences(FILE,0).edit().clear().apply();}
    public static Intent destination(Context context){SharedPreferences values=context.getSharedPreferences(FILE,0);String kind=values.getString(KIND,"");String track=values.getString(TRACK,"");if("book".equals(kind)&&!track.isEmpty())return new Intent(context,PlayerActivity.class).putExtra("book_id",values.getString(BOOK,"")).putExtra("track_id",track);if("music".equals(kind)){try{return new Intent(context,MusicPlayerActivity.class).putExtra("track_id",Long.parseLong(track));}catch(RuntimeException ignored){}}return new Intent(context,MainActivity.class);}
}
