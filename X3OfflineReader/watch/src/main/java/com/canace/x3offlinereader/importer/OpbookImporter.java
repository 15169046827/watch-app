package com.canace.x3offlinereader.importer;

import android.content.Context;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;
import org.json.JSONObject;

/** Staging -> limits/path/checksum/semantic validation -> atomic commit. */
public final class OpbookImporter {
    private static final int MAX_ENTRIES=4096;
    private static final long MAX_ENTRY=512L*1024*1024;
    private static final long MAX_TOTAL=4L*1024*1024*1024;
    private final Context context;
    private final LibraryDb db;

    public OpbookImporter(Context context,LibraryDb db){this.context=context.getApplicationContext();this.db=db;}

    public File inboxDir(){File base=context.getExternalFilesDir("inbox");if(base==null)base=new File(context.getFilesDir(),"inbox");base.mkdirs();return base;}

    public LibraryModels.Book importFile(File packageFile) throws Exception {
        if(!packageFile.isFile()||!packageFile.getName().toLowerCase(Locale.ROOT).endsWith(".opbook"))throw new IllegalArgumentException("Not an .opbook file");
        File base=context.getExternalFilesDir(null);if(base==null)base=context.getFilesDir();
        File staging=new File(base,"staging/"+UUID.randomUUID()); staging.mkdirs();
        try {
            extract(packageFile,staging); validateChecksums(staging);
            LibraryModels.Book book=new OpbookValidator().validate(staging);
            File books=new File(base,"books");books.mkdirs(); File destination=new File(books,book.id);
            if(destination.exists())throw new IllegalStateException("Book already imported: "+book.title);
            move(staging,destination); book.rootPath=destination.getAbsolutePath();
            try { db.insertBook(book); } catch(Exception error){delete(destination);throw error;}
            return book;
        } finally { if(staging.exists())delete(staging); }
    }

    private static void extract(File packageFile,File staging) throws Exception {
        Set<String> names=new HashSet<>();int count=0;long total=0;
        try(ZipFile zip=new ZipFile(packageFile)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();if(entry.isDirectory())continue;
                if(++count>MAX_ENTRIES)throw new IllegalArgumentException("Too many ZIP entries");
                String name=OpbookValidator.safe(entry.getName());if(!names.add(name))throw new IllegalArgumentException("Duplicate ZIP entry");
                if(entry.getSize()>MAX_ENTRY)throw new IllegalArgumentException("ZIP entry too large");
                if(entry.getSize()>1024*1024&&entry.getCompressedSize()>0&&entry.getSize()/Math.max(1,entry.getCompressedSize())>500)throw new IllegalArgumentException("Suspicious compression ratio");
                File target=new File(staging,name);File canonical=target.getCanonicalFile();String root=staging.getCanonicalPath()+File.separator;
                if(!canonical.getPath().startsWith(root))throw new IllegalArgumentException("ZIP path escape");
                File parent=canonical.getParentFile();if(!parent.mkdirs()&&!parent.isDirectory())throw new IllegalStateException("Cannot create directory");
                try(InputStream in=new BufferedInputStream(zip.getInputStream(entry));BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(canonical))){
                    byte[] buffer=new byte[64*1024];long item=0;for(int read;(read=in.read(buffer))!=-1;){if(read==0)continue;item+=read;total+=read;if(item>MAX_ENTRY||total>MAX_TOTAL)throw new IllegalArgumentException("Expanded ZIP exceeds limits");out.write(buffer,0,read);}
                }
            }
        }
        if(count==0)throw new IllegalArgumentException("Empty package");
    }

    private static void validateChecksums(File root) throws Exception {
        File checksumFile=new File(root,"checksums.json");if(!checksumFile.isFile())throw new IllegalArgumentException("Missing checksums.json");
        if(checksumFile.length()>16L*1024*1024)throw new IllegalArgumentException("checksums.json too large");
        JSONObject document=new JSONObject(new String(Files.readAllBytes(checksumFile.toPath()),StandardCharsets.UTF_8));
        if(!"1.0".equals(document.getString("formatVersion"))||!"SHA-256".equals(document.getString("algorithm")))throw new IllegalArgumentException("Unsupported checksums");
        JSONObject expected=document.getJSONObject("files");Set<String> actual=new HashSet<>();
        try(Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){paths.filter(Files::isRegularFile).forEach(path->{String relative=root.toPath().relativize(path).toString().replace('\\','/');if(!relative.equals("checksums.json")){if(!allowedFile(relative))throw new IllegalArgumentException("Unsupported package file: "+relative);actual.add(relative);}});}
        Set<String> listed=new HashSet<>();Iterator<String> keys=expected.keys();while(keys.hasNext()){String name=OpbookValidator.safe(keys.next());listed.add(name);File file=new File(root,name);if(!file.isFile()||!sha256(file).equals(expected.getString(name)))throw new IllegalArgumentException("Checksum mismatch: "+name);}
        if(!listed.equals(actual))throw new IllegalArgumentException("Checksum coverage mismatch");
    }

    private static String sha256(File file) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[64*1024];for(int n;(n=in.read(b))!=-1;)if(n>0)digest.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format("%02x",b));return s.toString();}
    private static boolean allowedFile(String path){return path.equals("manifest.json")||path.equals("cover.webp")||path.matches("content/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")||path.matches("timeline/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")||path.matches("audio/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.m4a");}
    private static void move(File from,File to) throws Exception {try{Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException error){Files.move(from.toPath(),to.toPath());}}
    private static void delete(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);}file.delete();}
}
