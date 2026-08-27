package com.canace.x3offlinereader;

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
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.OpbookImporter;
import com.canace.x3offlinereader.ui.ChaptersActivity;
import com.canace.x3offlinereader.ui.SwipeBackActivity;
import com.canace.x3offlinereader.ui.StoragePicker;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Round-safe novel library. Packages are copied to Android/data/.../files/inbox. */
public final class BookLibraryActivity extends SwipeBackActivity {
    private static final int PICK_OPBOOK=101;private LibraryDb db;private OpbookImporter importer;private final List<LibraryModels.BookSummary> books=new ArrayList<>();private ArrayAdapter<LibraryModels.BookSummary> adapter;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_book_library);enableSwipeBack(R.id.root);db=new LibraryDb(this);importer=new OpbookImporter(this,db);adapter=new ArrayAdapter<>(this,R.layout.item_library_entry,android.R.id.text1,books);ListView list=findViewById(R.id.book_list);list.setAdapter(adapter);list.setOnItemClickListener((parent,view,position,id)->{LibraryModels.BookSummary book=books.get(position);startActivity(new Intent(this,ChaptersActivity.class).putExtra("book_id",book.id).putExtra("book_title",book.title));});findViewById(R.id.import_button).setOnClickListener(view->importInbox());findViewById(R.id.choose_button).setOnClickListener(view->choosePackage());refresh();}
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){if(db==null)return;books.clear();books.addAll(db.books());adapter.notifyDataSetChanged();File inbox=importer.inboxDir();((TextView)findViewById(R.id.inbox_status)).setText(getString(R.string.book_library_status,books.size(),countPackages(inbox)));}
    private void importInbox(){File[] packages=importer.inboxDir().listFiles(file->file.isFile()&&file.getName().toLowerCase(Locale.ROOT).endsWith(".opbook"));if(packages==null||packages.length==0){Toast.makeText(this,R.string.no_packages,Toast.LENGTH_SHORT).show();setImportEnabled(true);return;}setImportEnabled(false);new Thread(()->{int imported=0;String error=null;for(int i=0;i<packages.length;i++){File file=packages[i];try{showStatus(file.getName(),i+1,packages.length);importer.importFile(file);archiveImported(file);imported++;}catch(Exception failure){error=file.getName()+": "+failure.getMessage();break;}}int result=imported;String message=error;runOnUiThread(()->{setImportEnabled(true);refresh();Toast.makeText(this,message==null?getString(R.string.import_complete,result):message,Toast.LENGTH_LONG).show();});},"opbook-import").start();}
    private void choosePackage(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream","application/x-zip-compressed"});startActivityForResult(StoragePicker.startInMusic(intent),PICK_OPBOOK);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=PICK_OPBOOK||resultCode!=RESULT_OK||data==null)return;setImportEnabled(false);new Thread(()->{try{ClipData clips=data.getClipData();int total=clips==null?(data.getData()==null?0:1):clips.getItemCount();if(clips!=null)for(int i=0;i<total;i++)copyPackage(clips.getItemAt(i).getUri(),i+1,total);else if(data.getData()!=null)copyPackage(data.getData(),1,1);runOnUiThread(this::importInbox);}catch(Exception error){runOnUiThread(()->{setImportEnabled(true);Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();});}},"opbook-copy").start();}
    private void copyPackage(Uri uri,int index,int count)throws Exception{String displayName=displayName(uri,"import.opbook");showStatus(displayName,index,count);String name=new File(displayName).getName().replaceAll("[^A-Za-z0-9._-]","_");if(!name.toLowerCase(Locale.ROOT).endsWith(".opbook"))name+=".opbook";File target=new File(importer.inboxDir(),System.currentTimeMillis()+"-"+name);long total=0;try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(target)){if(in==null)throw new IllegalArgumentException("无法打开文件");byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){if(n==0)continue;total+=n;if(total>4L*1024*1024*1024)throw new IllegalArgumentException("文件过大");out.write(buffer,0,n);}}catch(Exception error){target.delete();throw error;}}
    private String displayName(Uri uri,String fallback){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){return c!=null&&c.moveToFirst()&&!c.isNull(0)?c.getString(0):fallback;}}
    private void showStatus(String name,int index,int count){runOnUiThread(()->((TextView)findViewById(R.id.inbox_status)).setText(getString(R.string.importing_named_count,name,index,count)));}
    private void setImportEnabled(boolean enabled){findViewById(R.id.import_button).setEnabled(enabled);findViewById(R.id.choose_button).setEnabled(enabled);}
    private static int countPackages(File dir){File[] files=dir.listFiles(file->file.isFile()&&file.getName().toLowerCase(Locale.ROOT).endsWith(".opbook"));return files==null?0:files.length;}
    private static void archiveImported(File file){try{File directory=new File(file.getParentFile(),"processed");if(!directory.mkdirs()&&!directory.isDirectory())throw new IllegalStateException("Cannot create processed folder");File destination=new File(directory,file.getName());if(destination.exists())destination=new File(directory,System.currentTimeMillis()+"-"+file.getName());java.nio.file.Files.move(file.toPath(),destination.toPath());}catch(Exception ignored){file.delete();}}
    @Override protected void onDestroy(){if(db!=null)db.close();super.onDestroy();}
}
