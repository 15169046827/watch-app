package com.canace.x3offlinereader.builder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class BuilderCacheManager {
    static final long DEFAULT_MAX_BYTES=20L*1024*1024*1024;
    static final Duration DEFAULT_MAX_AGE=Duration.ofDays(180);
    record Stats(long sentenceFiles,long sentenceBytes,long unfinishedDirectories,long unfinishedBytes){}

    private final Path workRoot,cacheRoot;
    BuilderCacheManager(Path root){workRoot=root.resolve("builder/.work").toAbsolutePath().normalize();cacheRoot=workRoot.resolve("cache");}

    Stats stats(){long cacheFiles=0,cacheBytes=0,workDirs=0,workBytes=0;try(Stream<Path> paths=Files.list(cacheRoot)){for(Path path:paths.filter(Files::isRegularFile).toList()){cacheFiles++;cacheBytes+=size(path);}}catch(Exception ignored){}
        try(Stream<Path> paths=Files.list(workRoot)){for(Path path:paths.filter(Files::isDirectory).filter(this::isBookWork).toList()){workDirs++;workBytes+=treeSize(path);}}catch(Exception ignored){}return new Stats(cacheFiles,cacheBytes,workDirs,workBytes);}

    void afterSuccessfulBuild(Path completedWork,BuildProgress progress){try{deleteTree(completedWork);progress.update(99,"已清理本次转换临时 PCM/Track 文件");}catch(Exception failure){progress.update(99,"转换已完成，但本次临时目录稍后再清理");}try{PruneResult result=pruneSentenceCache(DEFAULT_MAX_BYTES,DEFAULT_MAX_AGE);if(result.deletedFiles>0)progress.update(99,"自动整理句子缓存：释放 "+humanBytes(result.deletedBytes));}catch(Exception ignored){}}

    record PruneResult(long deletedFiles,long deletedBytes){}
    PruneResult pruneSentenceCache(long maximumBytes,Duration maximumAge)throws Exception{
        if(!Files.isDirectory(cacheRoot))return new PruneResult(0,0);List<Path> files;try(Stream<Path> paths=Files.list(cacheRoot)){files=new ArrayList<>(paths.filter(Files::isRegularFile).toList());}
        files.sort(Comparator.comparing(this::modified));long total=0;for(Path file:files)total+=size(file);long cutoff=Instant.now().minus(maximumAge).toEpochMilli(),deleted=0,bytes=0;
        for(Path file:files){long length=size(file);boolean expired=modified(file).toMillis()<cutoff,over=total>maximumBytes;if(!expired&&!over)continue;Files.deleteIfExists(file);deleted++;bytes+=length;total-=length;}return new PruneResult(deleted,bytes);}

    PruneResult clearSentenceCache()throws Exception{if(!Files.isDirectory(cacheRoot))return new PruneResult(0,0);long files=0,bytes=0;try(Stream<Path> paths=Files.list(cacheRoot)){for(Path path:paths.filter(Files::isRegularFile).toList()){long length=size(path);if(Files.deleteIfExists(path)){files++;bytes+=length;}}}return new PruneResult(files,bytes);}
    PruneResult clearStaleWork(Duration age)throws Exception{if(!Files.isDirectory(workRoot))return new PruneResult(0,0);long cutoff=Instant.now().minus(age).toEpochMilli(),dirs=0,bytes=0;try(Stream<Path> paths=Files.list(workRoot)){for(Path path:paths.filter(Files::isDirectory).filter(this::isBookWork).toList()){if(modified(path).toMillis()>=cutoff)continue;long length=treeSize(path);deleteTree(path);dirs++;bytes+=length;}}return new PruneResult(dirs,bytes);}

    private boolean isBookWork(Path path){return path.getParent()!=null&&path.getParent().equals(workRoot)&&path.getFileName().toString().startsWith("book-");}
    private void deleteTree(Path target)throws Exception{Path normalized=target.toAbsolutePath().normalize();if(!isBookWork(normalized))throw new IllegalArgumentException("Unsafe work cleanup path");if(!Files.exists(normalized))return;try(Stream<Path> paths=Files.walk(normalized)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private static long treeSize(Path root){try(Stream<Path> paths=Files.walk(root)){return paths.filter(Files::isRegularFile).mapToLong(BuilderCacheManager::size).sum();}catch(Exception ignored){return 0;}}
    private static long size(Path path){try{return Files.size(path);}catch(Exception ignored){return 0;}}
    private FileTime modified(Path path){try{return Files.getLastModifiedTime(path);}catch(Exception ignored){return FileTime.fromMillis(0);}}
    static String humanBytes(long bytes){double value=bytes;String[] units={"B","KB","MB","GB","TB"};int unit=0;while(value>=1024&&unit<units.length-1){value/=1024;unit++;}return String.format(java.util.Locale.ROOT,"%.2f %s",value,units[unit]);}
}
