package com.canace.x3offlinereader.builder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/** Small, non-secret task registry. Book text and credentials are never stored here. */
final class BuildCheckpointStore {
    record Checkpoint(String id, Path file, BuilderOptions options, String state, int percent, String message, long updatedAt) {}

    private final Path directory;

    BuildCheckpointStore(Path root) { directory=root.resolve("builder/.work/checkpoints").toAbsolutePath().normalize(); }

    synchronized Checkpoint create(BuilderOptions options) throws Exception {
        Files.createDirectories(directory);
        String id=Instant.now().toEpochMilli()+"-"+UUID.randomUUID().toString().substring(0,8);
        Path file=directory.resolve(id+".properties");
        Properties values=fromOptions(id,options);
        values.setProperty("state","running");values.setProperty("percent","0");values.setProperty("message","准备转换");touch(values);
        write(file,values);
        return read(file);
    }

    synchronized Optional<Checkpoint> latest() {
        if(!Files.isDirectory(directory))return Optional.empty();
        try(Stream<Path> paths=Files.list(directory)){
            return paths.filter(path->path.getFileName().toString().endsWith(".properties"))
                    .map(this::readQuietly).flatMap(Optional::stream)
                    .filter(checkpoint->!"complete".equals(checkpoint.state()))
                    .max(Comparator.comparingLong(Checkpoint::updatedAt));
        }catch(Exception ignored){return Optional.empty();}
    }

    synchronized Checkpoint update(Checkpoint checkpoint,String state,int percent,String message) throws Exception {
        Properties values=load(checkpoint.file());
        values.setProperty("state",state);values.setProperty("percent",Integer.toString(Math.max(0,Math.min(100,percent))));
        if(message!=null&&!message.isBlank())values.setProperty("message",message);
        touch(values);write(checkpoint.file(),values);return read(checkpoint.file());
    }

    synchronized void complete(Checkpoint checkpoint) throws Exception { Files.deleteIfExists(checkpoint.file()); }

    private Checkpoint read(Path file) throws Exception {
        Properties values=load(file);BuilderOptions options=toOptions(values);
        return new Checkpoint(values.getProperty("id",strip(file)),file,options,values.getProperty("state","paused"),integer(values,"percent",0),values.getProperty("message",""),longValue(values,"updatedAt",Files.getLastModifiedTime(file).toMillis()));
    }
    private Optional<Checkpoint> readQuietly(Path file){try{return Optional.of(read(file));}catch(Exception ignored){return Optional.empty();}}

    private static Properties fromOptions(String id,BuilderOptions options){
        Properties values=new Properties();values.setProperty("id",id);values.setProperty("input.count",Integer.toString(options.inputs.size()));
        for(int i=0;i<options.inputs.size();i++)values.setProperty("input."+i,options.inputs.get(i).toAbsolutePath().normalize().toString());
        put(values,"output",options.output);put(values,"cover",options.cover);put(values,"meloPython",options.meloPython);put(values,"ffmpeg",options.ffmpeg);
        put(values,"title",options.title);put(values,"author",options.author);put(values,"language",options.language);put(values,"tts",options.tts);put(values,"voice",options.voice);
        values.setProperty("meloWorkers",Integer.toString(options.meloWorkers));values.setProperty("rate",Double.toString(options.rate));values.setProperty("pauseMs",Integer.toString(options.pauseMs));values.setProperty("maxTrackMinutes",Integer.toString(options.maxTrackMinutes));
        values.setProperty("createdAt",options.createdAt.toString());values.setProperty("separate",Boolean.toString(options.separate));values.setProperty("outputToSource",Boolean.toString(options.outputToSource));values.setProperty("allowOnlineTts",Boolean.toString(options.allowOnlineTts));return values;
    }

    private static BuilderOptions toOptions(Properties values){
        BuilderOptions options=new BuilderOptions();int count=integer(values,"input.count",0);for(int i=0;i<count;i++){String value=values.getProperty("input."+i,"");if(!value.isBlank())options.inputs.add(Path.of(value));}
        options.output=path(values,"output");options.cover=path(values,"cover");options.meloPython=path(values,"meloPython");options.ffmpeg=path(values,"ffmpeg");
        options.title=blankToNull(values.getProperty("title"));options.author=values.getProperty("author","");options.language=values.getProperty("language","zh-CN");options.tts=values.getProperty("tts","melo");options.voice=values.getProperty("voice","ZH");
        options.meloWorkers=integer(values,"meloWorkers",2);options.rate=decimal(values,"rate",1.0);options.pauseMs=integer(values,"pauseMs",250);options.maxTrackMinutes=integer(values,"maxTrackMinutes",20);
        try{options.createdAt=Instant.parse(values.getProperty("createdAt"));}catch(Exception ignored){}
        options.separate=Boolean.parseBoolean(values.getProperty("separate","false"));options.outputToSource=Boolean.parseBoolean(values.getProperty("outputToSource","false"));options.allowOnlineTts=Boolean.parseBoolean(values.getProperty("allowOnlineTts","false"));return options;
    }

    private static Properties load(Path file)throws Exception{Properties values=new Properties();try(InputStream input=Files.newInputStream(file)){values.load(input);}return values;}
    private static void write(Path file,Properties values)throws Exception{Files.createDirectories(file.getParent());Path temporary=file.resolveSibling(file.getFileName()+".tmp-"+UUID.randomUUID());try(OutputStream output=Files.newOutputStream(temporary)){values.store(output,"X3 Audiobook Builder resumable task");}try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private static void touch(Properties values){values.setProperty("updatedAt",Long.toString(System.currentTimeMillis()));}
    private static void put(Properties values,String key,Object value){if(value!=null)values.setProperty(key,value.toString());}
    private static Path path(Properties values,String key){String value=values.getProperty(key,"");return value.isBlank()?null:Path.of(value);}
    private static int integer(Properties values,String key,int fallback){try{return Integer.parseInt(values.getProperty(key));}catch(Exception ignored){return fallback;}}
    private static long longValue(Properties values,String key,long fallback){try{return Long.parseLong(values.getProperty(key));}catch(Exception ignored){return fallback;}}
    private static double decimal(Properties values,String key,double fallback){try{return Double.parseDouble(values.getProperty(key));}catch(Exception ignored){return fallback;}}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value;}
    private static String strip(Path file){String name=file.getFileName().toString();int dot=name.lastIndexOf('.');return dot<0?name:name.substring(0,dot);}
}
