package com.canace.x3offlinereader.importer;

import android.content.Context;
import com.canace.x3offlinereader.data.LibraryModels;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import org.json.JSONObject;

/** Extracts only the playback window from an indexed source package. */
public final class BookMediaCache {
    private static final long MAX_TRACK=512L*1024*1024,RESERVE=64L*1024*1024;
    private static final Object LOCK=new Object();
    private BookMediaCache(){}

    public static File cacheFile(Context context,String bookId,String entry){
        String safeBook=bookId.replaceAll("[^A-Za-z0-9._-]","_");String safeEntry=OpbookValidator.safe(entry);
        File base=context.getExternalFilesDir("book-cache");if(base==null)base=new File(context.getFilesDir(),"book-cache");return new File(new File(base,safeBook),safeEntry);
    }

    public static void prepare(Context context,String bookId,String sourceUri,String indexRoot,List<LibraryModels.TrackSummary> tracks)throws Exception{
        if(sourceUri==null||sourceUri.isEmpty())return;
        synchronized(LOCK){
            JSONObject expected=checksums(indexRoot);Map<String,LibraryModels.TrackSummary> pending=new HashMap<>();
            for(LibraryModels.TrackSummary track:tracks){File file=new File(track.audioPath);if(file.isFile()&&file.length()>=12){file.setLastModified(System.currentTimeMillis());continue;}pending.put(track.audioEntryPath,track);}
            if(pending.isEmpty())return;BookSourceAccess.SeekableZip seekable=BookSourceAccess.tryOpenZip(context,sourceUri);
            if(seekable!=null){try(BookSourceAccess.SeekableZip source=seekable){for(String name:new ArrayList<>(pending.keySet())){SeekableZipArchive.Entry entry=source.zip.getEntry(name);if(entry==null)continue;try(InputStream input=source.zip.open(entry)){extract(input,pending.get(name),expected.getString(name));}pending.remove(name);}}}
            else try(InputStream source=BookSourceAccess.open(context,sourceUri);ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source))){for(ZipEntry entry;(entry=zip.getNextEntry())!=null&&!pending.isEmpty();){if(entry.isDirectory()){zip.closeEntry();continue;}String name=OpbookValidator.safe(entry.getName());LibraryModels.TrackSummary track=pending.get(name);if(track==null){zip.closeEntry();continue;}extract(zip,track,expected.getString(name));zip.closeEntry();pending.remove(name);}}
            if(!pending.isEmpty())throw new IllegalArgumentException("源文件缺少播放音频");
        }
    }

    private static void extract(InputStream input,LibraryModels.TrackSummary track,String expected)throws Exception{
        File target=new File(track.audioPath),parent=target.getParentFile();if(!parent.mkdirs()&&!parent.isDirectory())throw new IllegalStateException("无法创建播放缓存");File partial=new File(parent,target.getName()+".part");partial.delete();MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024],header=new byte[12];int headerCount=0;long total=0;
        try(OutputStream output=new BufferedOutputStream(new FileOutputStream(partial))){for(int read;(read=input.read(buffer))!=-1;){if(read==0)continue;if(parent.getUsableSpace()<RESERVE)throw new IllegalStateException("存储空间不足，无法准备播放音频");digest.update(buffer,0,read);if(headerCount<header.length){int copy=Math.min(read,header.length-headerCount);System.arraycopy(buffer,0,header,headerCount,copy);headerCount+=copy;}total+=read;if(total>MAX_TRACK)throw new IllegalArgumentException("单个音频过大");output.write(buffer,0,read);}}
        catch(Exception failure){partial.delete();throw failure;}if(headerCount<12||header[4]!='f'||header[5]!='t'||header[6]!='y'||header[7]!='p'||!expected.equals(hex(digest.digest()))){partial.delete();throw new IllegalArgumentException("播放音频校验失败");}Files.move(partial.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);target.setLastModified(System.currentTimeMillis());
    }

    public static void retain(Context context,String bookId,List<LibraryModels.TrackSummary> tracks){
        synchronized(LOCK){Set<String> keep=new HashSet<>();for(LibraryModels.TrackSummary track:tracks)keep.add(new File(track.audioPath).getAbsolutePath());File root=cacheFile(context,bookId,"audio/placeholder.m4a").getParentFile().getParentFile();deleteExcept(root,keep);}
    }
    public static void clear(Context context,String bookId){synchronized(LOCK){File root=cacheFile(context,bookId,"audio/placeholder.m4a").getParentFile().getParentFile();OpbookImporter.delete(root);}}
    private static void deleteExcept(File file,Set<String> keep){if(file==null||!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteExcept(child,keep);File[] after=file.listFiles();if(after!=null&&after.length==0)file.delete();}else if(!keep.contains(file.getAbsolutePath())&&!file.getName().endsWith(".part"))file.delete();else if(file.getName().endsWith(".part"))file.delete();}
    private static JSONObject checksums(String indexRoot)throws Exception{File file=new File(indexRoot,"checksums.json");return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).getJSONObject("files");}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte item:bytes)value.append(String.format("%02x",item));return value.toString();}
}
