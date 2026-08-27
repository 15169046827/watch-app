package com.canace.x3offlinereader.data;

import java.util.ArrayList;
import java.util.List;

public final class LibraryModels {
    private LibraryModels() {}
    public static final class Book {
        public String id, title, author, rootPath;
        public final List<Volume> volumes = new ArrayList<>();
    }
    public static final class Volume {
        public String id, title; public int order;
        public final List<Chapter> chapters = new ArrayList<>();
    }
    public static final class Chapter {
        public String id, title, contentPath; public int order;
        public final List<Track> tracks = new ArrayList<>();
    }
    public static final class Track {
        public String id, audioPath, timelinePath; public int order; public long durationMs;
    }
    public static final class BookSummary {
        public final String id, title, author;public final boolean favorite;
        public BookSummary(String id, String title, String author,boolean favorite) { this.id = id; this.title = title; this.author = author;this.favorite=favorite; }
        @Override public String toString() { return (favorite?"★ ":"")+title + (author == null || author.isEmpty() ? "" : "\n" + author); }
    }
    public static final class ChapterSummary {
        public final String id, title, contentPath; public final long durationMs;
        public final List<TrackSummary> tracks;
        public ChapterSummary(String id,String title,String contentPath,long durationMs,List<TrackSummary> tracks){this.id=id;this.title=title;this.contentPath=contentPath;this.durationMs=durationMs;this.tracks=tracks;}
        @Override public String toString() { return title; }
    }
    public static final class TrackSummary {
        public final String id,audioPath,timelinePath;public final long durationMs,chapterOffsetMs;
        public TrackSummary(String id,String audioPath,String timelinePath,long durationMs,long chapterOffsetMs){this.id=id;this.audioPath=audioPath;this.timelinePath=timelinePath;this.durationMs=durationMs;this.chapterOffsetMs=chapterOffsetMs;}
    }
    public static final class ResumePoint {
        public final String chapterId,trackId;public final long trackPositionMs,chapterPositionMs,updatedAt;
        public ResumePoint(String chapterId,String trackId,long trackPositionMs,long chapterPositionMs,long updatedAt){this.chapterId=chapterId;this.trackId=trackId;this.trackPositionMs=trackPositionMs;this.chapterPositionMs=chapterPositionMs;this.updatedAt=updatedAt;}
    }
}
