package com.canace.x3offlinereader.data;

import java.util.ArrayList;
import java.util.List;

public final class LibraryModels {

    private LibraryModels() { }

    public static final class Book {

        public String id;

        public String title;

        public String author;

        public String rootPath;

        public final List<Volume> volumes = new ArrayList<>();
    }

    public static final class Volume {

        public String id;

        public String title;

        public int order;

        public final List<Chapter> chapters = new ArrayList<>();
    }

    public static final class Chapter {

        public String id;

        public String title;

        public String contentPath;

        public int order;

        public final List<Track> tracks = new ArrayList<>();
    }

    public static final class Track {

        public String id;

        public String audioPath;

        public String timelinePath;

        public int order;

        public long durationMs;
    }

    public static final class BookSummary {

        public final String id;

        public final String title;

        public final String author;

        public final String sourceUri;

        public final boolean favorite;

        public BookSummary(
                String id, String title, String author, String sourceUri, boolean favorite) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.sourceUri = sourceUri;
            this.favorite = favorite;
        }

        @Override
        public String toString() {
            return title + (author == null || author.isEmpty() ? "" : "\n" + author);
        }
    }

    public static final class ChapterSummary {

        public final String id;

        public final String title;

        public final String contentPath;

        public final long durationMs;

        public final List<TrackSummary> tracks;

        public ChapterSummary(
                String id,
                String title,
                String contentPath,
                long durationMs,
                List<TrackSummary> tracks) {
            this.id = id;
            this.title = title;
            this.contentPath = contentPath;
            this.durationMs = durationMs;
            this.tracks = tracks;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public static final class TrackSummary {

        public final String id;

        public final String audioPath;

        public final String audioEntryPath;

        public final String timelinePath;

        public final String sourceUri;

        public final long durationMs;

        public final long chapterOffsetMs;

        public TrackSummary(
                String id,
                String audioPath,
                String audioEntryPath,
                String timelinePath,
                String sourceUri,
                long durationMs,
                long chapterOffsetMs) {
            this.id = id;
            this.audioPath = audioPath;
            this.audioEntryPath = audioEntryPath;
            this.timelinePath = timelinePath;
            this.sourceUri = sourceUri;
            this.durationMs = durationMs;
            this.chapterOffsetMs = chapterOffsetMs;
        }
    }

    public static final class BookStorage {

        public final String id;

        public final String title;

        public final String rootPath;

        public final String sourceUri;

        public BookStorage(String id, String title, String rootPath, String sourceUri) {
            this.id = id;
            this.title = title;
            this.rootPath = rootPath;
            this.sourceUri = sourceUri;
        }

        public boolean indexedPackage() {
            return sourceUri != null && !sourceUri.isEmpty();
        }
    }

    public static final class ResumePoint {

        public final String chapterId;

        public final String trackId;

        public final long trackPositionMs;

        public final long chapterPositionMs;

        public final long updatedAt;

        public ResumePoint(
                String chapterId,
                String trackId,
                long trackPositionMs,
                long chapterPositionMs,
                long updatedAt) {
            this.chapterId = chapterId;
            this.trackId = trackId;
            this.trackPositionMs = trackPositionMs;
            this.chapterPositionMs = chapterPositionMs;
            this.updatedAt = updatedAt;
        }
    }
}
