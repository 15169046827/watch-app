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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MusicLibraryActivity extends SwipeBackActivity {
    private static final int PICK_MUSIC=201;
    private MusicLibraryDb db;private final List<MusicModels.Track> tracks=new ArrayList<>();private ArrayAdapter<MusicModels.Track> adapter;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_music_library);enableSwipeBack(R.id.root);db=new MusicLibraryDb(this);adapter=new ArrayAdapter<>(this,R.layout.item_library_entry,android.R.id.text1,tracks);ListView list=findViewById(R.id.music_list);list.setAdapter(adapter);list.setOnItemClickListener((p,v,index,id)->startActivity(new Intent(this,MusicPlayerActivity.class).putExtra("track_id",tracks.get(index).id)));findViewById(R.id.scan_button).setOnClickListener(v->scan());findViewById(R.id.add_music_button).setOnClickListener(v->choose());refresh();}
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){if(db==null)return;tracks.clear();tracks.addAll(db.tracks());adapter.notifyDataSetChanged();((TextView)findViewById(R.id.music_path)).setText(getString(R.string.music_library_status,tracks.size()));}
    private void scan(){int found=MusicScanner.scan(this,db);refresh();Toast.makeText(this,getString(R.string.scan_complete,found),Toast.LENGTH_SHORT).show();}
    private void choose(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/mpeg","audio/mp4","text/plain","application/octet-stream"});startActivityForResult(StoragePicker.startInMusic(intent),PICK_MUSIC);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=PICK_MUSIC||resultCode!=RESULT_OK||data==null)return;findViewById(R.id.add_music_button).setEnabled(false);new Thread(()->{try{ClipData clips=data.getClipData();int total=clips==null?(data.getData()==null?0:1):clips.getItemCount();if(clips!=null)for(int i=0;i<total;i++){showProgress(i+1,total);copy(clips.getItemAt(i).getUri());}else if(data.getData()!=null){showProgress(1,1);copy(data.getData());}runOnUiThread(()->{scan();findViewById(R.id.add_music_button).setEnabled(true);});}catch(Exception error){runOnUiThread(()->{findViewById(R.id.add_music_button).setEnabled(true);Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();});}},"music-copy").start();}
    private void showProgress(int current,int total){runOnUiThread(()->((TextView)findViewById(R.id.music_path)).setText(getString(R.string.importing_music,current,total)));}
    private void copy(Uri uri)throws Exception{String name="audio";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}name=new File(name).getName().replaceAll("[^A-Za-z0-9._-]","_");String lower=name.toLowerCase(Locale.ROOT);if(!lower.endsWith(".mp3")&&!lower.endsWith(".m4a")&&!lower.endsWith(".lrc"))throw new IllegalArgumentException("只支持 MP3、M4A 或 LRC");long limit=lower.endsWith(".lrc")?8L*1024*1024:1024L*1024*1024;File target=new File(MusicScanner.musicDir(this),name);if(target.exists())target=new File(target.getParentFile(),System.currentTimeMillis()+"-"+name);long total=0;try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(target)){if(in==null)throw new IllegalArgumentException("无法打开文件");byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){if(n==0)continue;total+=n;if(total>limit)throw new IllegalArgumentException("文件过大");out.write(buffer,0,n);}}catch(Exception error){target.delete();throw error;}}
    @Override protected void onDestroy(){db.close();super.onDestroy();}
}
