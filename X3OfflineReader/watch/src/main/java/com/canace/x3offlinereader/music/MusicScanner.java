package com.canace.x3offlinereader.music;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MusicScanner {
    private MusicScanner(){}
    public static File musicDir(Context context){File dir=context.getExternalFilesDir("music");if(dir==null)dir=new File(context.getFilesDir(),"music");dir.mkdirs();return dir;}
    public static int scan(Context context,MusicLibraryDb db){db.removeMissing();File dir=musicDir(context);File[] all=dir.listFiles(File::isFile);if(all==null)return 0;Map<String,File> lyrics=new HashMap<>();List<File> files=new ArrayList<>();for(File file:all){String name=file.getName().toLowerCase(Locale.ROOT);if(name.endsWith(".lrc")&&MusicFileInfo.isReadable(file))lyrics.put(MusicFileInfo.stem(file.getName()).toLowerCase(Locale.ROOT),file);else if(name.endsWith(".mp3")||name.endsWith(".m4a")||name.endsWith(".flac"))files.add(file);}files.sort((a,b)->Integer.compare(MusicFileInfo.displayQuality(b),MusicFileInfo.displayQuality(a)));Map<Long,List<File>> acceptedBySize=new HashMap<>();Set<String> indexed=new HashSet<>();int count=0;for(File file:files){boolean duplicate=false;List<File> sameSize=acceptedBySize.get(file.length());if(sameSize!=null)for(File accepted:sameSize)if(MusicFileInfo.sameContent(file,accepted)){duplicate=true;break;}if(duplicate)continue;acceptedBySize.computeIfAbsent(file.length(),ignored->new ArrayList<>()).add(file);MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(file.getAbsolutePath());MusicFileInfo.Labels labels=MusicFileInfo.labels(file.getName(),retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);File lrc=lyrics.get(MusicFileInfo.stem(file.getName()).toLowerCase(Locale.ROOT));db.upsert(file.getAbsolutePath(),labels.title,labels.artist,lrc==null?null:lrc.getAbsolutePath(),duration==null?0:Long.parseLong(duration));indexed.add(file.getAbsolutePath());count++;}catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}}db.removeUnscanned(indexed);return count;}
}
