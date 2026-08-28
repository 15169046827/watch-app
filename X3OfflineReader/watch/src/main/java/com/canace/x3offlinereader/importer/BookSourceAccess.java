package com.canace.x3offlinereader.importer;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import android.os.ParcelFileDescriptor;

/** Persistent SAF source access without copying the selected package. */
public final class BookSourceAccess {
    private BookSourceAccess(){}

    public static InputStream open(Context context,String sourceUri)throws Exception{
        Uri uri=Uri.parse(sourceUri);
        if("file".equalsIgnoreCase(uri.getScheme()))return new FileInputStream(new File(uri.getPath()));
        InputStream input=context.getContentResolver().openInputStream(uri);
        if(input==null)throw new SourceMissingException();
        return input;
    }

    public static boolean exists(Context context,String sourceUri){
        if(sourceUri==null||sourceUri.isEmpty())return true;
        try(InputStream ignored=open(context,sourceUri)){return true;}catch(Exception missing){return false;}
    }
    public static SeekableZip tryOpenZip(Context context,String sourceUri){
        try{Uri uri=Uri.parse(sourceUri);if("file".equalsIgnoreCase(uri.getScheme()))return new SeekableZip(SeekableZipArchive.open(new File(uri.getPath())));ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(uri,"r");if(descriptor==null)return null;return new SeekableZip(SeekableZipArchive.open(new ParcelFileDescriptor.AutoCloseInputStream(descriptor)));}catch(Exception failure){return null;}
    }

    public static boolean delete(Context context,String sourceUri){
        try{
            Uri uri=Uri.parse(sourceUri);
            if("file".equalsIgnoreCase(uri.getScheme()))return new File(uri.getPath()).delete();
            return DocumentsContract.deleteDocument(context.getContentResolver(),uri);
        }catch(Exception failure){return false;}
    }
    public static void release(Context context,String sourceUri){
        if(sourceUri==null||sourceUri.isEmpty()||"file".equalsIgnoreCase(Uri.parse(sourceUri).getScheme()))return;
        Uri uri=Uri.parse(sourceUri);try{context.getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){try{context.getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignoredAgain){}}
    }

    public static final class SourceMissingException extends Exception {
        public SourceMissingException(){super("源文件不存在或已失去访问权限");}
    }
    public static final class SeekableZip implements AutoCloseable {
        final SeekableZipArchive zip;
        SeekableZip(SeekableZipArchive zip){this.zip=zip;}
        @Override public void close()throws Exception{zip.close();}
    }
}
