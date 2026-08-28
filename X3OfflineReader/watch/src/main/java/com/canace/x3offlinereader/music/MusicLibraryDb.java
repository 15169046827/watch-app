package com.canace.x3offlinereader.music;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.io.File;

/** Physically separate music catalog and progress database. */
public final class MusicLibraryDb extends SQLiteOpenHelper {
    public MusicLibraryDb(Context context){super(context,"music_library.db",null,3);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT,path TEXT UNIQUE NOT NULL,title TEXT NOT NULL,artist TEXT,lrc_path TEXT,duration_ms INTEGER NOT NULL,scanned_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE progress(track_id INTEGER PRIMARY KEY,position_ms INTEGER NOT NULL,updated_at INTEGER NOT NULL,FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)");
        createV2(db);
        createV3(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion<2)createV2(db);if(oldVersion<3)createV3(db);}
    private static void createV2(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS favorites(track_id INTEGER PRIMARY KEY,created_at INTEGER NOT NULL,FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)");db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");}
    private static void createV3(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS hidden_tracks(path TEXT PRIMARY KEY,hidden_at INTEGER NOT NULL)");}
    @Override public void onConfigure(SQLiteDatabase db){super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true);}
    public void upsert(String path,String title,String artist,String lrcPath,long durationMs){SQLiteDatabase db=getWritableDatabase();try(Cursor c=db.rawQuery("SELECT 1 FROM hidden_tracks WHERE path=?",new String[]{path})){if(c.moveToFirst())return;}ContentValues v=new ContentValues();v.put("path",path);v.put("title",title);v.put("artist",artist);v.put("lrc_path",lrcPath);v.put("duration_ms",durationMs);v.put("scanned_at",System.currentTimeMillis());if(db.update("tracks",v,"path=?",new String[]{path})==0)db.insertOrThrow("tracks",null,v);}
    public List<MusicModels.Track> tracks(){List<MusicModels.Track> result=new ArrayList<>();String sql="SELECT t.id,t.title,t.artist,t.path,t.lrc_path,t.duration_ms,CASE WHEN f.track_id IS NULL THEN 0 ELSE 1 END FROM tracks t LEFT JOIN favorites f ON f.track_id=t.id ORDER BY t.title COLLATE NOCASE";try(Cursor c=getReadableDatabase().rawQuery(sql,null)){while(c.moveToNext())result.add(new MusicModels.Track(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getInt(6)!=0));}return result;}
    public void saveProgress(long id,long ms){ContentValues v=new ContentValues();v.put("track_id",id);v.put("position_ms",ms);v.put("updated_at",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("progress",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public long progress(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT position_ms FROM progress WHERE track_id=?",new String[]{Long.toString(id)})){return c.moveToFirst()?c.getLong(0):0;}}
    public void toggleFavorite(long id){SQLiteDatabase db=getWritableDatabase();int removed=db.delete("favorites","track_id=?",new String[]{Long.toString(id)});if(removed==0){ContentValues v=new ContentValues();v.put("track_id",id);v.put("created_at",System.currentTimeMillis());db.insert("favorites",null,v);}}
    public void removeMissing(){SQLiteDatabase db=getWritableDatabase();List<Long> missing=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id,path FROM tracks",null)){while(c.moveToNext())if(!new java.io.File(c.getString(1)).isFile())missing.add(c.getLong(0));}for(Long id:missing)db.delete("tracks","id=?",new String[]{id.toString()});}
    public String settingValue(String key,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}}
    public void putSetting(String key,String value){ContentValues v=new ContentValues();v.put("key",key);v.put("value",value);getWritableDatabase().insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public boolean removeTrack(long id,boolean deleteSource){SQLiteDatabase db=getWritableDatabase();String path=null,lrc=null;try(Cursor c=db.rawQuery("SELECT path,lrc_path FROM tracks WHERE id=?",new String[]{Long.toString(id)})){if(c.moveToFirst()){path=c.getString(0);lrc=c.getString(1);}}if(path==null)return false;File audio=new File(path);if(deleteSource&&audio.exists()&&!audio.delete())return false;db.beginTransaction();try{if(!deleteSource){ContentValues hidden=new ContentValues();hidden.put("path",path);hidden.put("hidden_at",System.currentTimeMillis());db.insertWithOnConflict("hidden_tracks",null,hidden,SQLiteDatabase.CONFLICT_REPLACE);}db.delete("tracks","id=?",new String[]{Long.toString(id)});db.setTransactionSuccessful();}finally{db.endTransaction();}if(deleteSource&&lrc!=null)new File(lrc).delete();return true;}
}
