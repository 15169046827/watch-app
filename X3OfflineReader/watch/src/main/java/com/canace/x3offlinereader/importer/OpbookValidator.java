package com.canace.x3offlinereader.importer;

import com.canace.x3offlinereader.data.LibraryModels;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;

final class OpbookValidator {
    LibraryModels.Book validate(File root) throws Exception {
        JSONObject manifest=json(root,"manifest.json");
        require("opbook".equals(manifest.getString("format")) && "1.0".equals(manifest.getString("formatVersion")),"unsupported format");
        JSONObject bookJson=manifest.getJSONObject("book"); LibraryModels.Book book=new LibraryModels.Book();
        book.id=id(bookJson.getString("bookId")); book.title=nonempty(bookJson.getString("title")); book.author=bookJson.getJSONArray("authors").optString(0,""); book.rootPath=root.getAbsolutePath();
        Set<String> ids=new HashSet<>(); require(ids.add(book.id),"duplicate id");
        Set<String> referenced=new HashSet<>();referenced.add("manifest.json");referenced.add("checksums.json");
        require("checksums.json".equals(manifest.getString("checksumsPath")),"checksums path");
        if(bookJson.has("coverPath")){String cover=safe(bookJson.getString("coverPath"));require("cover.webp".equals(cover),"cover path");File file=new File(root,cover);require(file.isFile()&&file.length()<=8L*1024*1024,"cover");validateWebp(file);referenced.add(cover);}
        JSONArray volumes=manifest.getJSONArray("volumes"); require(volumes.length()>0,"no volumes");
        for(int vi=0;vi<volumes.length();vi++) {
            JSONObject source=volumes.getJSONObject(vi); require(source.getInt("order")==vi,"volume order");
            LibraryModels.Volume volume=new LibraryModels.Volume(); volume.id=id(source.getString("volumeId")); require(ids.add(volume.id),"duplicate id"); volume.title=nonempty(source.getString("title")); volume.order=vi;
            JSONArray chapters=source.getJSONArray("chapters"); require(chapters.length()>0,"no chapters");
            for(int ci=0;ci<chapters.length();ci++) {
                JSONObject chapterJson=chapters.getJSONObject(ci); require(chapterJson.getInt("order")==ci,"chapter order");
                LibraryModels.Chapter chapter=new LibraryModels.Chapter(); chapter.id=id(chapterJson.getString("chapterId")); require(ids.add(chapter.id),"duplicate id"); chapter.title=nonempty(chapterJson.getString("title")); chapter.order=ci;
                chapter.contentPath=resource(chapterJson.getString("contentPath"),"content","json");referenced.add(chapter.contentPath); JSONObject content=json(root,chapter.contentPath); require("1.0".equals(content.getString("formatVersion"))&&chapter.id.equals(content.getString("chapterId")),"content identity");
                JSONObject blocks=new JSONObject(); JSONArray blockArray=content.getJSONArray("blocks");
                String displayTitle=null,fallbackTitle=null;
                for(int bi=0;bi<blockArray.length();bi++){JSONObject block=blockArray.getJSONObject(bi); require(block.getInt("order")==bi,"block order"); String blockId=id(block.getString("blockId")); require(!blocks.has(blockId),"duplicate block");String blockText=block.getString("text"); blocks.put(blockId,blockText);String normalized=blockText.trim().replaceAll("\\s+"," ");if(fallbackTitle==null&&!normalized.isEmpty())fallbackTitle=normalized;if(displayTitle==null&&"paragraph".equals(block.optString("type"))&&!normalized.isEmpty())displayTitle=normalized;}
                if(displayTitle==null)displayTitle=fallbackTitle;
                if(displayTitle!=null)chapter.title=displayTitle;
                JSONArray tracks=chapterJson.getJSONArray("tracks"); require(tracks.length()>0,"no tracks");
                for(int ti=0;ti<tracks.length();ti++) {
                    JSONObject trackJson=tracks.getJSONObject(ti); require(trackJson.getInt("order")==ti,"track order");
                    LibraryModels.Track track=new LibraryModels.Track(); track.id=id(trackJson.getString("trackId")); require(ids.add(track.id),"duplicate id"); track.order=ti; track.durationMs=trackJson.getLong("durationMs"); require(track.durationMs>0,"duration");
                    require("audio/mp4".equals(trackJson.getString("mimeType")),"mime"); track.audioPath=resource(trackJson.getString("audioPath"),"audio","m4a"); track.timelinePath=resource(trackJson.getString("timelinePath"),"timeline","json");
                    referenced.add(track.audioPath);referenced.add(track.timelinePath);
                    require(new File(root,track.audioPath).isFile(),"missing audio"); validateM4a(new File(root,track.audioPath));
                    JSONObject timeline=json(root,track.timelinePath); require("1.0".equals(timeline.getString("formatVersion"))&&track.id.equals(timeline.getString("trackId")) && chapter.id.equals(timeline.getString("chapterId")),"timeline identity"); require(track.durationMs==timeline.getLong("durationMs"),"timeline duration");
                    validateSegments(timeline.getJSONArray("segments"),blocks,track.durationMs); chapter.tracks.add(track);
                }
                volume.chapters.add(chapter);
            }
            book.volumes.add(volume);
        }
        rejectUnreferencedManagedFiles(root,referenced);
        return book;
    }

    private static void rejectUnreferencedManagedFiles(File root,Set<String> referenced)throws Exception{try(Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){paths.filter(Files::isRegularFile).forEach(path->{String relative=root.toPath().relativize(path).toString().replace('\\','/');if((relative.startsWith("content/")||relative.startsWith("timeline/")||relative.startsWith("audio/"))&&!referenced.contains(relative))throw new IllegalArgumentException("unreferenced resource");});}}

    private static void validateSegments(JSONArray segments,JSONObject blocks,long duration) throws Exception {
        long prior=0; Set<String> ids=new HashSet<>(); require(segments.length()>0,"no segments");
        for(int i=0;i<segments.length();i++){JSONObject s=segments.getJSONObject(i); require(ids.add(id(s.getString("segmentId"))),"duplicate segment"); long start=s.getLong("startMs"),end=s.getLong("endMs"); require(prior<=start && start<end && end<=duration,"timeline range"); prior=end; String text=blocks.getString(s.getString("blockId")); int a=s.getInt("startChar"),b=s.getInt("endChar"); int count=text.codePointCount(0,text.length()); require(0<=a&&a<b&&b<=count,"text range"); String slice=text.substring(text.offsetByCodePoints(0,a),text.offsetByCodePoints(0,b)); require(slice.equals(s.getString("text")),"text mismatch");}
    }
    private static void validateM4a(File file) throws Exception { byte[] h=new byte[12]; try(FileInputStream in=new FileInputStream(file)){require(in.read(h)==12 && h[4]=='f'&&h[5]=='t'&&h[6]=='y'&&h[7]=='p',"invalid m4a");} }
    private static void validateWebp(File file)throws Exception{byte[] h=new byte[12];try(FileInputStream in=new FileInputStream(file)){require(in.read(h)==12&&h[0]=='R'&&h[1]=='I'&&h[2]=='F'&&h[3]=='F'&&h[8]=='W'&&h[9]=='E'&&h[10]=='B'&&h[11]=='P',"invalid webp");}}
    private static JSONObject json(File root,String relative) throws Exception { File file=new File(root,safe(relative)); require(file.isFile()&&file.length()<=16L*1024*1024,"missing/large json"); return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)); }
    static String safe(String value){require(value!=null&&!value.isEmpty()&&!value.startsWith("/")&&!value.contains("\\")&&!value.contains("\0")&&!value.contains("//")&&!value.contains(":"),"unsafe path"); for(String p:value.split("/",-1))require(!p.isEmpty()&&!p.equals(".")&&!p.equals(".."),"unsafe path"); return value;}
    private static String resource(String value,String directory,String extension){String safe=safe(value);require(safe.matches(directory+"/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\."+extension),"invalid resource path");return safe;}
    private static String id(String value){require(value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"),"invalid id");return value;}
    private static String nonempty(String value){require(value!=null&&!value.trim().isEmpty(),"empty text");return value;}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
