package com.canace.x3offlinereader;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.music.MusicScanner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Proves that the target Watch firmware can parse and decode FLAC before App support is enabled. */
public final class WatchFlacCapabilityProbeTest extends InstrumentationTestCase {
    public void testSystemParsesAndDecodesFlac() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        File sample=new File(MusicScanner.musicDir(context),"device-capability.flac");
        copyAsset(getInstrumentation().getContext(),"device-smoke.flac",sample);
        MediaMetadataRetriever metadata=new MediaMetadataRetriever();
        MediaPlayer player=new MediaPlayer();
        try {
            metadata.setDataSource(sample.getAbsolutePath());
            String duration=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull(duration);assertTrue(Long.parseLong(duration)>=2500);
            player.setDataSource(sample.getAbsolutePath());player.setVolume(0f,0f);player.prepare();
            assertTrue(player.getDuration()>=2500);player.start();Thread.sleep(900);
            assertTrue("FLAC decoder must advance playback",player.getCurrentPosition()>=500);
        } finally {
            player.release();metadata.release();sample.delete();
        }
    }

    private static void copyAsset(Context context,String name,File target) throws Exception {
        File parent=target.getParentFile();assertTrue(parent.mkdirs()||parent.isDirectory());
        try(InputStream in=context.getAssets().open(name);FileOutputStream out=new FileOutputStream(target)){
            byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)if(n>0)out.write(buffer,0,n);
        }
    }
}
