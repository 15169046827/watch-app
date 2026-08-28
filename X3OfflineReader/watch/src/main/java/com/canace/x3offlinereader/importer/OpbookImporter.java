package com.canace.x3offlinereader.importer;

import android.content.Context;
import android.net.Uri;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Stream validation -> metadata-only index -> atomic database registration. */
public final class OpbookImporter {
    private static final int MAX_ENTRIES=4096;
    private static final long MAX_ENTRY=512L*1024*1024,MAX_TOTAL=4L*1024*1024*1024,MAX_METADATA=256L*1024*1024;
    private final Context context;private final LibraryDb db;
    public OpbookImporter(Context context,LibraryDb db){this.context=context.getApplicationContext();this.db=db;File base=this.context.getExternalFilesDir(null);if(base==null)base=this.context.getFilesDir();delete(new File(base,"book-index-staging"));}

    public LibraryModels.Book indexDocument(Uri source)throws Exception{
        File base=context.getExternalFilesDir(null);if(base==null)base=context.getFilesDir();
        File staging=new File(base,"book-index-staging/"+UUID.randomUUID());if(!staging.mkdirs())throw new IllegalStateException("无法创建索引目录");
        try{
            ScanResult scanned=scan(source.toString(),staging);validateChecksums(staging,scanned);
            LibraryModels.Book book=new OpbookValidator().validate(staging);
            if(db.containsBook(book.id))throw new AlreadyImportedException(book.title);
            delete(new File(staging,"audio"));
            File indexes=new File(base,"book-indexes");if(!indexes.mkdirs()&&!indexes.isDirectory())throw new IllegalStateException("无法创建书库索引");
            File destination=new File(indexes,book.id);if(destination.exists())delete(destination);move(staging,destination);book.rootPath=destination.getAbsolutePath();
            try{db.insertBook(book,source.toString());}catch(Exception error){delete(destination);throw error;}
            return book;
        }finally{if(staging.exists())delete(staging);}
    }

    private ScanResult scan(String sourceUri,File staging)throws Exception{
        BookSourceAccess.SeekableZip seekable=BookSourceAccess.tryOpenZip(context,sourceUri);
        if(seekable!=null)try(BookSourceAccess.SeekableZip source=seekable){return scanSeekable(source.zip,staging);}
        return scanSequential(sourceUri,staging);
    }

    private ScanResult scanSeekable(ZipFile zip,File staging)throws Exception{
        Set<String> names=new HashSet<>();Map<String,String> hashes=new HashMap<>();int count=0;long total=0,metadata=0;
        Enumeration<? extends ZipEntry> entries=zip.entries();
        while(entries.hasMoreElements()){
            ZipEntry entry=entries.nextElement();if(entry.isDirectory())continue;
            if(++count>MAX_ENTRIES)throw new IllegalArgumentException("压缩包文件数量过多");
            String name=OpbookValidator.safe(entry.getName());if(!names.add(name))throw new IllegalArgumentException("压缩包存在重复路径");if(!allowedFile(name))throw new IllegalArgumentException("不支持的包内文件");
            long limit=entryLimit(name),size=entry.getSize(),compressed=entry.getCompressedSize();if(size<0||size>limit)throw new IllegalArgumentException("包内文件过大");total+=size;if(total>MAX_TOTAL)throw new IllegalArgumentException("压缩包展开大小超过限制");if(size>1024*1024&&compressed>0&&size/Math.max(1,compressed)>500)throw new IllegalArgumentException("压缩比异常");
            File target=new File(staging,name);File parent=target.getParentFile();if(!parent.mkdirs()&&!parent.isDirectory())throw new IllegalStateException("无法创建索引目录");
            if(name.startsWith("audio/")){
                byte[] header=new byte[12];try(InputStream input=zip.getInputStream(entry)){readHeader(input,header);}validateM4a(header);try(OutputStream placeholder=new FileOutputStream(target)){placeholder.write(header);}continue;
            }
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long item=0;byte[] buffer=new byte[64*1024];try(InputStream input=zip.getInputStream(entry);OutputStream output=new BufferedOutputStream(new FileOutputStream(target))){for(int read;(read=input.read(buffer))!=-1;){if(read==0)continue;item+=read;metadata+=read;if(item>limit||metadata>MAX_METADATA)throw new IllegalArgumentException("书籍索引数据过大");digest.update(buffer,0,read);output.write(buffer,0,read);}}
            if(item!=size)throw new IllegalArgumentException("包内文件大小异常");if(!"checksums.json".equals(name))hashes.put(name,hex(digest.digest()));
        }
        if(count==0)throw new IllegalArgumentException("空的 .opbook 文件");return new ScanResult(names,hashes,true);
    }

    private ScanResult scanSequential(String sourceUri,File staging)throws Exception{
        Set<String> names=new HashSet<>();Map<String,String> hashes=new HashMap<>();int count=0;long total=0,metadata=0;
        try(InputStream source=BookSourceAccess.open(context,sourceUri);ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source))){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                if(entry.isDirectory()){zip.closeEntry();continue;}
                if(++count>MAX_ENTRIES)throw new IllegalArgumentException("压缩包文件数量过多");
                String name=OpbookValidator.safe(entry.getName());if(!names.add(name))throw new IllegalArgumentException("压缩包存在重复路径");if(!allowedFile(name))throw new IllegalArgumentException("不支持的包内文件");
                long limit=entryLimit(name);if(entry.getSize()>limit)throw new IllegalArgumentException("包内文件过大");
                boolean audio=name.startsWith("audio/");File target=new File(staging,name);File parent=target.getParentFile();if(!parent.mkdirs()&&!parent.isDirectory())throw new IllegalStateException("无法创建索引目录");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] header=new byte[12],buffer=new byte[64*1024];int headerCount=0;long item=0;
                try(OutputStream output=audio?null:new BufferedOutputStream(new FileOutputStream(target))){
                    long untilYield=4L*1024*1024;for(int read;(read=zip.read(buffer))!=-1;){if(read==0)continue;digest.update(buffer,0,read);if(headerCount<header.length){int copy=Math.min(read,header.length-headerCount);System.arraycopy(buffer,0,header,headerCount,copy);headerCount+=copy;}item+=read;total+=read;untilYield-=read;if(untilYield<=0){android.os.SystemClock.sleep(2);untilYield=4L*1024*1024;}if(item>limit||total>MAX_TOTAL)throw new IllegalArgumentException("压缩包展开大小超过限制");if(!audio){metadata+=read;if(metadata>MAX_METADATA)throw new IllegalArgumentException("书籍索引数据过大");output.write(buffer,0,read);}}
                }
                zip.closeEntry();long compressed=entry.getCompressedSize();if(item>1024*1024&&compressed>0&&item/Math.max(1,compressed)>500)throw new IllegalArgumentException("压缩比异常");
                if(audio){if(headerCount<12||header[4]!='f'||header[5]!='t'||header[6]!='y'||header[7]!='p')throw new IllegalArgumentException("音频格式无效");try(OutputStream placeholder=new FileOutputStream(target)){placeholder.write(header);}}
                if(!"checksums.json".equals(name))hashes.put(name,hex(digest.digest()));
            }
        }
        if(count==0)throw new IllegalArgumentException("空的 .opbook 文件");return new ScanResult(names,hashes,false);
    }

    private static void validateChecksums(File staging,ScanResult scanned)throws Exception{
        File file=new File(staging,"checksums.json");if(!file.isFile()||file.length()>16L*1024*1024)throw new IllegalArgumentException("缺少或过大的 checksums.json");
        JSONObject document=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));if(!"1.0".equals(document.getString("formatVersion"))||!"SHA-256".equals(document.getString("algorithm")))throw new IllegalArgumentException("不支持的校验格式");
        JSONObject expected=document.getJSONObject("files");Set<String> listed=new HashSet<>();Iterator<String> keys=expected.keys();while(keys.hasNext()){String name=OpbookValidator.safe(keys.next());String checksum=expected.getString(name);listed.add(name);if(!checksum.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("文件校验值无效");String actual=scanned.hashes.get(name);if(actual!=null){if(!checksum.equals(actual))throw new IllegalArgumentException("文件校验失败");}else if(!scanned.deferredAudio||!name.startsWith("audio/"))throw new IllegalArgumentException("文件校验失败");}
        Set<String> archive=new HashSet<>(scanned.names);archive.remove("checksums.json");if(!listed.equals(archive))throw new IllegalArgumentException("校验清单不完整");
    }
    private static void readHeader(InputStream input,byte[] header)throws Exception{int offset=0;while(offset<header.length){int read=input.read(header,offset,header.length-offset);if(read<0)break;if(read>0)offset+=read;}if(offset<header.length)throw new IllegalArgumentException("音频格式无效");}
    private static void validateM4a(byte[] header){if(header[4]!='f'||header[5]!='t'||header[6]!='y'||header[7]!='p')throw new IllegalArgumentException("音频格式无效");}
    private static final class ScanResult {final Set<String> names;final Map<String,String> hashes;final boolean deferredAudio;ScanResult(Set<String> names,Map<String,String> hashes,boolean deferredAudio){this.names=names;this.hashes=hashes;this.deferredAudio=deferredAudio;}}
    private static long entryLimit(String name){if("cover.webp".equals(name))return 8L*1024*1024;if(name.endsWith(".json"))return 16L*1024*1024;return MAX_ENTRY;}
    private static boolean allowedFile(String path){return path.equals("manifest.json")||path.equals("checksums.json")||path.equals("cover.webp")||path.matches("content/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")||path.matches("timeline/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")||path.matches("audio/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.m4a");}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte item:bytes)value.append(String.format("%02x",item));return value.toString();}
    private static void move(File from,File to)throws Exception{try{Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException failure){Files.move(from.toPath(),to.toPath());}}
    public static void delete(File file){if(file==null||!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);}file.delete();}
    public static final class AlreadyImportedException extends Exception{AlreadyImportedException(String title){super("《"+title+"》已经在书库中");}}
}
