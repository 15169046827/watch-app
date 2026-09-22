package com.canace.x3offlinereader.builder.model;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral normalized model shared by TXT/EPUB parsers and the packager. */
public final class BookModel {
    private BookModel() { }

    public static final class Book {
        public String bookId;
        public String title;
        public final List<String> authors = new ArrayList<>();
        public String language = "zh-CN";
        public final List<Volume> volumes = new ArrayList<>();
    }

    public static final class Volume {
        public String volumeId;
        public String title;
        public int order;
        public final List<Chapter> chapters = new ArrayList<>();
    }

    public static final class Chapter {
        public String chapterId;
        public String title;
        public int order;
        public final List<Block> blocks = new ArrayList<>();
    }

    public static final class Block {
        public String blockId;
        public int order;
        public String type;
        public String text;
    }
}
