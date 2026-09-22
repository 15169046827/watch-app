package com.canace.x3offlinereader.music;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MusicScanner {
    private MusicScanner() { }

    public static File musicDir(Context context) {
        File dir = context.getExternalFilesDir("music");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "music");
        }
        dir.mkdirs();
        return dir;
    }

    public static int scan(Context context, MusicLibraryDb db) {
        db.removeMissing();
        File[] all = musicDir(context).listFiles(File::isFile);
        if (all == null) {
            return 0;
        }
        Map<String, File> lyrics = new HashMap<>();
        List<File> files = new ArrayList<>();
        classify(all, lyrics, files);
        files.sort(
                (left, right) ->
                        Integer.compare(
                                MusicFileInfo.displayQuality(right),
                                MusicFileInfo.displayQuality(left)));
        return index(files, lyrics, db);
    }

    private static void classify(
            File[] all, Map<String, File> lyrics, List<File> audioFiles) {
        for (File file : all) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".lrc") && MusicFileInfo.isReadable(file)) {
                lyrics.put(MusicFileInfo.stem(file.getName()).toLowerCase(Locale.ROOT), file);
            } else if (isAudio(name)) {
                audioFiles.add(file);
            }
        }
    }

    private static boolean isAudio(String name) {
        return name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".flac");
    }

    private static int index(
            List<File> files, Map<String, File> lyrics, MusicLibraryDb database) {
        Map<Long, List<File>> acceptedBySize = new HashMap<>();
        Set<String> indexed = new HashSet<>();
        int count = 0;
        for (File file : files) {
            if (duplicate(file, acceptedBySize.get(file.length()))) {
                continue;
            }
            acceptedBySize.computeIfAbsent(file.length(), ignored -> new ArrayList<>()).add(file);
            if (indexFile(file, lyrics, database)) {
                indexed.add(file.getAbsolutePath());
                count++;
            }
        }
        database.removeUnscanned(indexed);
        return count;
    }

    private static boolean duplicate(File file, List<File> accepted) {
        if (accepted == null) {
            return false;
        }
        for (File candidate : accepted) {
            if (MusicFileInfo.sameContent(file, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean indexFile(
            File file, Map<String, File> lyrics, MusicLibraryDb database) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            MusicFileInfo.Labels labels =
                    MusicFileInfo.labels(
                            file.getName(),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            String duration =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            File lrc = lyrics.get(MusicFileInfo.stem(file.getName()).toLowerCase(Locale.ROOT));
            database.upsert(
                    file.getAbsolutePath(),
                    labels.title,
                    labels.artist,
                    lrc == null ? null : lrc.getAbsolutePath(),
                    duration == null ? 0 : Long.parseLong(duration));
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            release(retriever);
        }
    }

    private static void release(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Exception ignored) {
        }
    }
}
