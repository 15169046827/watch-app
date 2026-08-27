package com.canace.x3offlinereader.music;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcReader {
    private static final Pattern LINE=Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)");
    private static final class Item{final long ms;final String text;Item(long ms,String text){this.ms=ms;this.text=text;}}
    private final List<Item> items=new ArrayList<>();
    public LrcReader(String path){if(path==null)return;try{File file=new File(path);if(!file.isFile()||file.length()>8L*1024*1024)return;for(String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)){Matcher m=LINE.matcher(line);if(m.matches()){long fraction=m.group(3)==null?0:Long.parseLong(m.group(3));if(m.group(3)!=null&&m.group(3).length()==2)fraction*=10;else if(m.group(3)!=null&&m.group(3).length()==1)fraction*=100;items.add(new Item(Long.parseLong(m.group(1))*60000+Long.parseLong(m.group(2))*1000+fraction,m.group(4).trim()));}}items.sort(Comparator.comparingLong(item->item.ms));}catch(Exception ignored){}}
    public String at(long ms){String current="";for(Item item:items){if(item.ms>ms)break;current=item.text;}return current;}
}
