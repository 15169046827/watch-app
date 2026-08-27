package com.canace.x3offlinereader.music;

public final class MusicModels {
    private MusicModels(){}
    public static final class Track {
        public final long id,durationMs;public final String title,artist,path,lrcPath;public final boolean favorite;
        public Track(long id,String title,String artist,String path,String lrcPath,long durationMs,boolean favorite){this.id=id;this.title=title;this.artist=artist;this.path=path;this.lrcPath=lrcPath;this.durationMs=durationMs;this.favorite=favorite;}
        @Override public String toString(){return (favorite?"★ ":"")+title+(artist==null||artist.isEmpty()?"":"\n"+artist);}
    }
}
