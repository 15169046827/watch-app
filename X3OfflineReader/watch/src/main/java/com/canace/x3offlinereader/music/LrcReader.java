package com.canace.x3offlinereader.music;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcReader {
    private static final Pattern TIME=Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET=Pattern.compile("\\[offset:([+-]?\\d+)]",Pattern.CASE_INSENSITIVE);
    private static final class Item{final long ms;final String text;Item(long ms,String text){this.ms=ms;this.text=text;}}
    public static final class Window{public final String previous,current,next;Window(String previous,String current,String next){this.previous=previous;this.current=current;this.next=next;}}
    private final List<Item> items=new ArrayList<>();
    public LrcReader(String path){if(path==null)return;try{File file=new File(path);if(!file.isFile()||file.length()>8L*1024*1024)return;String content=decode(Files.readAllBytes(file.toPath()));long offset=0;Matcher offsetMatch=OFFSET.matcher(content);if(offsetMatch.find())offset=Long.parseLong(offsetMatch.group(1));for(String line:content.split("\\r\\n|\\n|\\r")){Matcher matcher=TIME.matcher(line);List<Long> times=new ArrayList<>();int textStart=-1,cursor=0;while(matcher.find()){if(!line.substring(cursor,matcher.start()).trim().isEmpty())break;long fraction=matcher.group(3)==null?0:Long.parseLong(matcher.group(3));if(matcher.group(3)!=null&&matcher.group(3).length()==2)fraction*=10;else if(matcher.group(3)!=null&&matcher.group(3).length()==1)fraction*=100;times.add(Long.parseLong(matcher.group(1))*60000+Long.parseLong(matcher.group(2))*1000+fraction);textStart=matcher.end();cursor=matcher.end();}if(textStart>=0){String text=TIME.matcher(line.substring(textStart)).replaceAll("").trim();if(!text.isEmpty())for(Long time:times)items.add(new Item(Math.max(0,time+offset),text));}}items.sort(Comparator.comparingLong(item->item.ms));}catch(Exception ignored){}}
    public String at(long ms){return window(ms).current;}
    public Window window(long ms){int index=-1;for(int i=0;i<items.size();i++){if(items.get(i).ms>ms)break;index=i;}if(index<0)return new Window("","",distinctAfter(-1,""));String current=items.get(index).text;return new Window(distinctBefore(index,current),current,distinctAfter(index,current));}
    private String distinctBefore(int index,String current){for(int i=index-1;i>=0;i--){String candidate=items.get(i).text;if(!candidate.equals(current))return candidate;}return "";}
    private String distinctAfter(int index,String current){for(int i=index+1;i<items.size();i++){String candidate=items.get(i).text;if(!candidate.equals(current))return candidate;}return "";}

    static String decode(byte[] bytes)throws Exception{
        if(starts(bytes,0xEF,0xBB,0xBF))return strict(Arrays.copyOfRange(bytes,3,bytes.length),StandardCharsets.UTF_8);
        if(starts(bytes,0xFF,0xFE))return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
        if(starts(bytes,0xFE,0xFF))return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
        try{return strict(bytes,StandardCharsets.UTF_8);}catch(CharacterCodingException ignored){return strict(bytes,Charset.forName("GB18030"));}
    }
    private static String strict(byte[] bytes,Charset charset)throws CharacterCodingException{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    private static boolean starts(byte[] bytes,int... prefix){if(bytes.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if((bytes[i]&0xff)!=prefix[i])return false;return true;}
}
