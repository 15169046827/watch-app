package com.canace.x3offlinereader.music;

import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.ui.SwipeBackActivity;
import com.canace.x3offlinereader.ui.StoragePicker;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MusicLibraryActivity extends SwipeBackActivity {
    private static final int PICK_MUSIC=201;
    private MusicLibraryDb db;private final List<MusicModels.Track> tracks=new ArrayList<>();private ArrayAdapter<MusicModels.Track> adapter;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_music_library);enableSwipeBack(R.id.root);db=new MusicLibraryDb(this);adapter=new ArrayAdapter<>(this,R.layout.item_library_entry,android.R.id.text1,tracks);ListView list=findViewById(R.id.music_list);list.setAdapter(adapter);list.setOnItemClickListener((p,v,index,id)->startActivity(new Intent(this,MusicPlayerActivity.class).putExtra("track_id",tracks.get(index).id)));list.setOnItemLongClickListener((p,v,index,id)->{offerDelete(tracks.get(index));return true;});findViewById(R.id.scan_button).setOnClickListener(v->scan());findViewById(R.id.add_music_button).setOnClickListener(v->choose());refresh();}
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){if(db==null)return;tracks.clear();tracks.addAll(db.tracks());adapter.notifyDataSetChanged();((TextView)findViewById(R.id.music_path)).setText(getString(R.string.music_library_status,tracks.size()));}
    private void scan(){int found=MusicScanner.scan(this,db);refresh();Toast.makeText(this,getString(R.string.scan_complete,found),Toast.LENGTH_SHORT).show();}
    private void choose(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/mpeg","audio/mp4","audio/flac","audio/x-flac","text/plain","application/octet-stream"});startActivityForResult(StoragePicker.startInMusic(intent),PICK_MUSIC);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=PICK_MUSIC||resultCode!=RESULT_OK||data==null)return;findViewById(R.id.add_music_button).setEnabled(false);new Thread(()->{try{ClipData clips=data.getClipData();int total=clips==null?(data.getData()==null?0:1):clips.getItemCount();if(clips!=null)for(int i=0;i<total;i++){showProgress(i+1,total);copy(clips.getItemAt(i).getUri());}else if(data.getData()!=null){showProgress(1,1);copy(data.getData());}runOnUiThread(()->{scan();findViewById(R.id.add_music_button).setEnabled(true);});}catch(Exception error){runOnUiThread(()->{findViewById(R.id.add_music_button).setEnabled(true);Toast.makeText(this,R.string.music_import_failed,Toast.LENGTH_LONG).show();});}},"music-copy").start();}
    private void showProgress(int current,int total){runOnUiThread(()->((TextView)findViewById(R.id.music_path)).setText(getString(R.string.importing_music,current,total)));}
    private void copy(Uri uri)throws Exception{String name="audio";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}name=MusicFileInfo.safeFileName(name);String lower=name.toLowerCase(Locale.ROOT);if(!lower.endsWith(".mp3")&&!lower.endsWith(".m4a")&&!lower.endsWith(".flac")&&!lower.endsWith(".lrc"))throw new IllegalArgumentException("只支持 MP3、M4A、FLAC 或 LRC");long limit=lower.endsWith(".lrc")?8L*1024*1024:1024L*1024*1024;File dir=MusicScanner.musicDir(this),temporary=File.createTempFile(".music-import-",".tmp",dir);long total=0;try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temporary)){if(in==null)throw new IllegalArgumentException("无法打开文件");byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){if(n==0)continue;total+=n;if(total>limit)throw new IllegalArgumentException("文件过大");out.write(buffer,0,n);}}catch(Exception error){temporary.delete();throw error;}File target=new File(dir,name);if(target.isFile()&&MusicFileInfo.sameContent(temporary,target)){temporary.delete();return;}if(!target.exists()){File[] candidates=dir.listFiles(file->file.isFile()&&file.length()==temporary.length()&&file.getName().toLowerCase(Locale.ROOT).endsWith(lower.substring(lower.lastIndexOf('.'))));if(candidates!=null)for(File candidate:candidates)if(MusicFileInfo.sameContent(temporary,candidate)&&candidate.renameTo(target)){temporary.delete();return;}}if(target.exists()){String stem=MusicFileInfo.stem(name),extension=name.substring(stem.length());for(int i=2;target.exists();i++)target=new File(dir,stem+" ("+i+")"+extension);}if(!temporary.renameTo(target)){temporary.delete();throw new IllegalStateException("无法保存文件");}}
    private void offerDelete(MusicModels.Track track){RoundChoiceDialog.show(this,getString(R.string.delete_music_title,track.title),new String[]{getString(R.string.remove_from_music_library),getString(R.string.delete_music_with_source)},-1,which->confirmDelete(track,which==1));}
    private void confirmDelete(MusicModels.Track track,boolean source){RoundChoiceDialog.show(this,source?getString(R.string.confirm_delete_music_source):getString(R.string.confirm_remove_music),new String[]{getString(R.string.confirm),getString(android.R.string.cancel)},-1,which->{if(which==0){boolean removed=db.removeTrack(track.id,source);if(removed){stopService(new Intent(this,PlaybackService.class));PlaybackTargetStore.clear(this);refresh();}Toast.makeText(this,removed?R.string.music_removed:R.string.music_delete_failed,Toast.LENGTH_LONG).show();}});}
    @Override protected void onDestroy(){db.close();super.onDestroy();}
}
