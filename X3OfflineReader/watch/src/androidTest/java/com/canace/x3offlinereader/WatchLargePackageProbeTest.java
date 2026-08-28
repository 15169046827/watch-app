package com.canace.x3offlinereader;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.importer.OpbookImporter;
import java.io.File;

/** Re-indexes an already registered large package without logging its private name or URI. */
public final class WatchLargePackageProbeTest extends InstrumentationTestCase {
    public void testLargePersistedPackageIndexesWithinTenSecondsPerGiB() throws Exception {
        Context context=getInstrumentation().getTargetContext();Uri selected=null;long sourceSize=0;
        try(LibraryDb db=new LibraryDb(context)){
            for(UriPermission permission:context.getContentResolver().getPersistedUriPermissions()){
                Uri uri=permission.getUri();if(!db.containsSource(uri.toString()))continue;
                try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(uri,"r")){long size=descriptor==null?-1:descriptor.getStatSize();if(size>sourceSize){sourceSize=size;selected=uri;}}
            }
            if(selected==null||sourceSize<1024L*1024*1024)return;
            long started=android.os.SystemClock.elapsedRealtime();try{new OpbookImporter(context,db).indexDocument(selected);fail("Expected existing book identity");}catch(OpbookImporter.AlreadyImportedException expected){}
            long elapsed=android.os.SystemClock.elapsedRealtime()-started,after;try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(selected,"r")){after=descriptor==null?-1:descriptor.getStatSize();}
            assertEquals(sourceSize,after);File base=context.getExternalFilesDir(null);if(base==null)base=context.getFilesDir();File staging=new File(base,"book-index-staging");File[] leftovers=staging.listFiles();assertTrue(leftovers==null||leftovers.length==0);
            long budget=Math.max(10000L,(sourceSize*10000L+1024L*1024*1024-1)/(1024L*1024*1024));System.out.println("large-index bytes="+sourceSize+" elapsedMs="+elapsed+" budgetMs="+budget);assertTrue("Large package index exceeded ten seconds per GiB: "+elapsed+" ms",elapsed<=budget);
        }
    }
}
