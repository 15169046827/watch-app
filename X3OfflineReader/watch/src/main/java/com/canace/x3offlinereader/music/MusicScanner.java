package com.canace.x3offlinereader.music;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.util.Locale;

public final class MusicScanner {
    private MusicScanner(){}
    public static File musicDir(Context context){File dir=context.getExternalFilesDir("music");if(dir==null)dir=new File(context.getFilesDir(),"music");dir.mkdirs();return dir;}
    public static int scan(Context context,MusicLibraryDb db){db.removeMissing();File[] files=musicDir(context).listFiles(file->{String n=file.getName().toLowerCase(Locale.ROOT);return file.isFile()&&(n.endsWith(".mp3")||n.endsWith(".m4a")||n.endsWith(".flac"));});if(files==null)return 0;int count=0;for(File file:files){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());String title=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);String artist=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);String base=file.getName().replaceFirst("\\.[^.]+$","");File lrc=new File(file.getParentFile(),base+".lrc");db.upsert(file.getAbsolutePath(),title==null||title.isEmpty()?base:title,artist,lrc.isFile()?lrc.getAbsolutePath():null,d==null?0:Long.parseLong(d));count++;}catch(Exception ignored){}finally{try{r.release();}catch(Exception ignored){}}}return count;}
}
