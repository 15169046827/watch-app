package com.canace.x3offlinereader.importer;

import android.content.Context;
import com.canace.x3offlinereader.data.LibraryModels;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Extracts only the playback window from an indexed source package. */
public final class BookMediaCache {

    private static final long MAX_TRACK = 512L * 1024 * 1024;

    private static final long RESERVE = 64L * 1024 * 1024;

    private static final Object LOCK = new Object();

    private BookMediaCache() { }

    public static File cacheFile(Context context, String bookId, String entry) {
        String safeBook = bookId.replaceAll("[^A-Za-z0-9._-]", "_");
        String safeEntry = OpbookValidator.safe(entry);
        File base = context.getExternalFilesDir("book-cache");
        if (base == null) {
            base = new File(context.getFilesDir(), "book-cache");
        }
        return new File(new File(base, safeBook), safeEntry);
    }

    public static void prepare(
            Context context,
            String bookId,
            String sourceUri,
            String indexRoot,
            List<LibraryModels.TrackSummary> tracks)
            throws Exception {
        if (sourceUri == null || sourceUri.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            JSONObject expected = checksums(indexRoot);
            Map<String, LibraryModels.TrackSummary> pending = pending(tracks);
            if (pending.isEmpty()) {
                return;
            }
            extractPending(context, sourceUri, pending, expected);
            if (!pending.isEmpty()) {
                throw new IllegalArgumentException("源文件缺少播放音频");
            }
        }
    }

    private static Map<String, LibraryModels.TrackSummary> pending(
            List<LibraryModels.TrackSummary> tracks) {
        Map<String, LibraryModels.TrackSummary> result = new HashMap<>();
        for (LibraryModels.TrackSummary track : tracks) {
            File file = new File(track.audioPath);
            if (file.isFile() && file.length() >= 12) {
                file.setLastModified(System.currentTimeMillis());
            } else {
                result.put(track.audioEntryPath, track);
            }
        }
        return result;
    }

    private static void extractPending(
            Context context,
            String sourceUri,
            Map<String, LibraryModels.TrackSummary> pending,
            JSONObject expected)
            throws Exception {
        BookSourceAccess.SeekableZip seekable = BookSourceAccess.tryOpenZip(context, sourceUri);
        if (seekable == null) {
            extractSequential(context, sourceUri, pending, expected);
        } else {
            extractSeekable(seekable, pending, expected);
        }
    }

    private static void extractSeekable(
            BookSourceAccess.SeekableZip seekable,
            Map<String, LibraryModels.TrackSummary> pending,
            JSONObject expected)
            throws Exception {
        try (BookSourceAccess.SeekableZip source = seekable) {
            for (String name : new ArrayList<>(pending.keySet())) {
                SeekableZipArchive.Entry entry = source.zip.getEntry(name);
                if (entry == null) {
                    continue;
                }
                try (InputStream input = source.zip.open(entry)) {
                    extract(input, pending.get(name), expected.getString(name));
                }
                pending.remove(name);
            }
        }
    }

    private static void extractSequential(
            Context context,
            String sourceUri,
            Map<String, LibraryModels.TrackSummary> pending,
            JSONObject expected)
            throws Exception {
        try (InputStream source = BookSourceAccess.open(context, sourceUri);
                ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null && !pending.isEmpty(); ) {
                extractSequentialEntry(zip, entry, pending, expected);
                zip.closeEntry();
            }
        }
    }

    private static void extractSequentialEntry(
            ZipInputStream zip,
            ZipEntry entry,
            Map<String, LibraryModels.TrackSummary> pending,
            JSONObject expected)
            throws Exception {
        if (entry.isDirectory()) {
            return;
        }
        String name = OpbookValidator.safe(entry.getName());
        LibraryModels.TrackSummary track = pending.get(name);
        if (track == null) {
            return;
        }
        extract(zip, track, expected.getString(name));
        pending.remove(name);
    }
    private static void extract(
            InputStream input, LibraryModels.TrackSummary track, String expected) throws Exception {
        File target = new File(track.audioPath);
        File parent = target.getParentFile();
        ensureDirectory(parent);
        File partial = new File(parent, target.getName() + ".part");
        partial.delete();
        CopyResult result;
        try {
            result = copy(input, partial, parent);
        } catch (Exception failure) {
            partial.delete();
            throw failure;
        }
        verify(result, expected, partial);
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        target.setLastModified(System.currentTimeMillis());
    }

    private static void ensureDirectory(File parent) {
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("无法创建播放缓存");
        }
    }

    private static CopyResult copy(InputStream input, File partial, File parent) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        byte[] header = new byte[12];
        int headerCount = 0;
        long total = 0;
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (read == 0) {
                    continue;
                }
                requireSpace(parent);
                digest.update(buffer, 0, read);
                headerCount = copyHeader(buffer, read, header, headerCount);
                total += read;
                requireTrackLimit(total);
                output.write(buffer, 0, read);
            }
        }
        return new CopyResult(header, headerCount, hex(digest.digest()));
    }

    private static void requireSpace(File parent) {
        if (parent.getUsableSpace() < RESERVE) {
            throw new IllegalStateException("存储空间不足，无法准备播放音频");
        }
    }

    private static int copyHeader(byte[] buffer, int read, byte[] header, int count) {
        if (count >= header.length) {
            return count;
        }
        int copied = Math.min(read, header.length - count);
        System.arraycopy(buffer, 0, header, count, copied);
        return count + copied;
    }

    private static void requireTrackLimit(long total) {
        if (total > MAX_TRACK) {
            throw new IllegalArgumentException("单个音频过大");
        }
    }

    private static void verify(CopyResult result, String expected, File partial) {
        byte[] header = result.header;
        boolean valid =
                result.headerCount >= 12
                        && header[4] == 'f'
                        && header[5] == 't'
                        && header[6] == 'y'
                        && header[7] == 'p'
                        && expected.equals(result.digest);
        if (!valid) {
            partial.delete();
            throw new IllegalArgumentException("播放音频校验失败");
        }
    }

    private static final class CopyResult {

        final byte[] header;

        final int headerCount;

        final String digest;

        CopyResult(byte[] header, int headerCount, String digest) {
            this.header = header;
            this.headerCount = headerCount;
            this.digest = digest;
        }
    }
    public static void retain(
            Context context, String bookId, List<LibraryModels.TrackSummary> tracks) {
        synchronized (LOCK) {
            Set<String> keep = new HashSet<>();
            for (LibraryModels.TrackSummary track : tracks) {
                keep.add(new File(track.audioPath).getAbsolutePath());
            }
            File root =
                    cacheFile(context, bookId, "audio/placeholder.m4a")
                            .getParentFile()
                            .getParentFile();
            deleteExcept(root, keep);
        }
    }

    public static void clear(Context context, String bookId) {
        synchronized (LOCK) {
            File root =
                    cacheFile(context, bookId, "audio/placeholder.m4a")
                            .getParentFile()
                            .getParentFile();
            OpbookImporter.delete(root);
        }
    }

    private static void deleteExcept(File file, Set<String> keep) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteExcept(child, keep);
                }
            }
            File[] after = file.listFiles();
            if (after != null && after.length == 0) {
                file.delete();
            }
        } else {
            if (!keep.contains(file.getAbsolutePath()) && !file.getName().endsWith(".part")) {
                file.delete();
            } else {
                if (file.getName().endsWith(".part")) {
                    file.delete();
                }
            }
        }
    }

    private static JSONObject checksums(String indexRoot) throws Exception {
        File file = new File(indexRoot, "checksums.json");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                .getJSONObject("files");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }
}
