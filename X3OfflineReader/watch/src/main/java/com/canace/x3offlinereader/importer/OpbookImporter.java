package com.canace.x3offlinereader.importer;

import android.content.Context;
import android.net.Uri;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Stream validation -> metadata-only index -> atomic database registration. */
public final class OpbookImporter {

    private static final int MAX_ENTRIES = 4096;

    private static final long MAX_ENTRY = 512L * 1024 * 1024;

    private static final long MAX_TOTAL = 4L * 1024 * 1024 * 1024;

    private static final long MAX_METADATA = 256L * 1024 * 1024;

    private static final byte[] INDEXED_AUDIO_PLACEHOLDER = {
        0, 0, 0, 12, 'f', 't', 'y', 'p', 0, 0, 0, 0
    };

    private final Context context;

    private final LibraryDb db;

    public OpbookImporter(Context context, LibraryDb db) {
        this.context = context.getApplicationContext();
        this.db = db;
        File base = this.context.getExternalFilesDir(null);
        if (base == null) {
            base = this.context.getFilesDir();
        }
        delete(new File(base, "book-index-staging"));
    }

    public LibraryModels.Book indexDocument(Uri source) throws Exception {
        File base = baseDirectory();
        File staging = stagingDirectory(base);
        try {
            ScanResult scanned = scan(source.toString(), staging);
            validateChecksums(staging, scanned);
            LibraryModels.Book book = new OpbookValidator().validate(staging);
            installIndex(base, staging, source, book);
            return book;
        } finally {
            if (staging.exists()) {
                delete(staging);
            }
        }
    }

    private File baseDirectory() {
        File base = context.getExternalFilesDir(null);
        return base == null ? context.getFilesDir() : base;
    }

    private static File stagingDirectory(File base) {
        File staging = new File(base, "book-index-staging/" + UUID.randomUUID());
        if (!staging.mkdirs()) {
            throw new IllegalStateException("无法创建索引目录");
        }
        return staging;
    }

    private void installIndex(
            File base, File staging, Uri source, LibraryModels.Book book) throws Exception {
        if (db.containsBook(book.id)) {
            throw new AlreadyImportedException(book.title);
        }
        delete(new File(staging, "audio"));
        File indexes = new File(base, "book-indexes");
        ensureDirectory(indexes);
        File destination = new File(indexes, book.id);
        if (destination.exists()) {
            delete(destination);
        }
        move(staging, destination);
        book.rootPath = destination.getAbsolutePath();
        try {
            db.insertBook(book, source.toString());
        } catch (Exception error) {
            delete(destination);
            throw error;
        }
    }

    private ScanResult scan(String sourceUri, File staging) throws Exception {
        BookSourceAccess.SeekableZip seekable = BookSourceAccess.tryOpenZip(context, sourceUri);
        if (seekable != null) {
            try (BookSourceAccess.SeekableZip source = seekable) {
                return scanSeekable(source.zip, staging);
            }
        }
        return scanSequential(sourceUri, staging);
    }

    private ScanResult scanSeekable(SeekableZipArchive zip, File staging) throws Exception {
        ScanState state = new ScanState();
        for (SeekableZipArchive.Entry entry : zip.entries()) {
            if (!entry.name.endsWith("/")) {
                scanSeekableEntry(zip, staging, entry, state);
            }
        }
        state.requireNotEmpty();
        return state.result(true);
    }

    private static void scanSeekableEntry(
            SeekableZipArchive zip,
            File staging,
            SeekableZipArchive.Entry entry,
            ScanState state)
            throws Exception {
        String name = state.accept(entry.name);
        long limit = entryLimit(name);
        state.acceptDeclaredSize(entry.size, limit);
        requireCompressionRatio(entry.size, entry.compressedSize);
        File target = target(staging, name);
        if (name.startsWith("audio/")) {
            writePlaceholder(target, INDEXED_AUDIO_PLACEHOLDER);
            return;
        }
        String hash = extractSeekableMetadata(zip, entry, target, state, limit);
        state.recordHash(name, hash);
    }

    private static String extractSeekableMetadata(
            SeekableZipArchive zip,
            SeekableZipArchive.Entry entry,
            File target,
            ScanState state,
            long limit)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long item = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = zip.open(entry);
                OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (read == 0) {
                    continue;
                }
                item += read;
                state.addMetadata(read);
                if (item > limit) {
                    throw new IllegalArgumentException("书籍索引数据过大");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (item != entry.size) {
            throw new IllegalArgumentException("包内文件大小异常");
        }
        return hex(digest.digest());
    }

    private ScanResult scanSequential(String sourceUri, File staging) throws Exception {
        ScanState state = new ScanState();
        try (InputStream source = BookSourceAccess.open(context, sourceUri);
                ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) {
                    scanSequentialEntry(zip, staging, entry, state);
                }
                zip.closeEntry();
            }
        }
        state.requireNotEmpty();
        return state.result(false);
    }

    private static void scanSequentialEntry(
            ZipInputStream zip, File staging, ZipEntry entry, ScanState state) throws Exception {
        String name = state.accept(entry.getName());
        long limit = entryLimit(name);
        if (entry.getSize() > limit) {
            throw new IllegalArgumentException("包内文件过大");
        }
        boolean audio = name.startsWith("audio/");
        File target = target(staging, name);
        ReadResult result = readSequential(zip, target, audio, limit, state);
        requireCompressionRatio(result.size, entry.getCompressedSize());
        if (audio) {
            requireAudioHeader(result.header, result.headerCount);
            writePlaceholder(target, result.header);
        }
        state.recordHash(name, result.digest);
    }

    private static ReadResult readSequential(
            ZipInputStream zip, File target, boolean audio, long limit, ScanState state)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] header = new byte[12];
        byte[] buffer = new byte[64 * 1024];
        int headerCount = 0;
        long item = 0;
        long untilYield = 4L * 1024 * 1024;
        try (OutputStream output =
                audio ? null : new BufferedOutputStream(new FileOutputStream(target))) {
            for (int read; (read = zip.read(buffer)) != -1; ) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                headerCount = copyHeader(buffer, read, header, headerCount);
                item += read;
                state.addTotal(read);
                untilYield = yieldPeriodically(untilYield, read);
                requireExpandedLimits(item, state.total, limit);
                if (!audio) {
                    state.addMetadata(read);
                    output.write(buffer, 0, read);
                }
            }
        }
        return new ReadResult(header, headerCount, item, hex(digest.digest()));
    }

    private static int copyHeader(byte[] buffer, int read, byte[] header, int count) {
        if (count >= header.length) {
            return count;
        }
        int copied = Math.min(read, header.length - count);
        System.arraycopy(buffer, 0, header, count, copied);
        return count + copied;
    }

    private static long yieldPeriodically(long remaining, int read) {
        long next = remaining - read;
        if (next <= 0) {
            android.os.SystemClock.sleep(2);
            return 4L * 1024 * 1024;
        }
        return next;
    }

    private static void requireExpandedLimits(long item, long total, long limit) {
        if (item > limit || total > MAX_TOTAL) {
            throw new IllegalArgumentException("压缩包展开大小超过限制");
        }
    }

    private static void requireCompressionRatio(long size, long compressed) {
        if (size > 1024 * 1024 && compressed > 0 && size / Math.max(1, compressed) > 500) {
            throw new IllegalArgumentException("压缩比异常");
        }
    }

    private static void requireAudioHeader(byte[] header, int count) {
        if (count < 12
                || header[4] != 'f'
                || header[5] != 't'
                || header[6] != 'y'
                || header[7] != 'p') {
            throw new IllegalArgumentException("音频格式无效");
        }
    }

    private static File target(File staging, String name) {
        File target = new File(staging, name);
        ensureDirectory(target.getParentFile());
        return target;
    }

    private static void ensureDirectory(File directory) {
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("无法创建索引目录");
        }
    }

    private static void writePlaceholder(File target, byte[] bytes) throws Exception {
        try (OutputStream placeholder = new FileOutputStream(target)) {
            placeholder.write(bytes);
        }
    }

    private static final class ScanState {

        final Set<String> names = new HashSet<>();

        final Map<String, String> hashes = new HashMap<>();

        int count;

        long total;

        long metadata;

        String accept(String rawName) {
            if (++count > MAX_ENTRIES) {
                throw new IllegalArgumentException("压缩包文件数量过多");
            }
            String name = OpbookValidator.safe(rawName);
            if (!names.add(name)) {
                throw new IllegalArgumentException("压缩包存在重复路径");
            }
            if (!allowedFile(name)) {
                throw new IllegalArgumentException("不支持的包内文件");
            }
            return name;
        }

        void acceptDeclaredSize(long size, long limit) {
            if (size < 0 || size > limit) {
                throw new IllegalArgumentException("包内文件过大");
            }
            if (size > MAX_TOTAL - total) {
                throw new IllegalArgumentException("压缩包展开大小超过限制");
            }
            total += size;
        }

        void addTotal(int bytes) {
            total += bytes;
        }

        void addMetadata(int bytes) {
            metadata += bytes;
            if (metadata > MAX_METADATA) {
                throw new IllegalArgumentException("书籍索引数据过大");
            }
        }

        void recordHash(String name, String hash) {
            if (!"checksums.json".equals(name)) {
                hashes.put(name, hash);
            }
        }

        void requireNotEmpty() {
            if (count == 0) {
                throw new IllegalArgumentException("空的 .opbook 文件");
            }
        }

        ScanResult result(boolean seekable) {
            return new ScanResult(names, hashes, seekable);
        }
    }

    private static final class ReadResult {

        final byte[] header;

        final int headerCount;

        final long size;

        final String digest;

        ReadResult(byte[] header, int headerCount, long size, String digest) {
            this.header = header;
            this.headerCount = headerCount;
            this.size = size;
            this.digest = digest;
        }
    }
    private static void validateChecksums(File staging, ScanResult scanned) throws Exception {
        File file = new File(staging, "checksums.json");
        if (!file.isFile() || file.length() > 16L * 1024 * 1024) {
            throw new IllegalArgumentException("缺少或过大的 checksums.json");
        }
        JSONObject document =
                new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (!"1.0".equals(document.getString("formatVersion"))
                || !"SHA-256".equals(document.getString("algorithm"))) {
            throw new IllegalArgumentException("不支持的校验格式");
        }
        JSONObject expected = document.getJSONObject("files");
        Set<String> listed = new HashSet<>();
        Iterator<String> keys = expected.keys();
        while (keys.hasNext()) {
            String name = OpbookValidator.safe(keys.next());
            String checksum = expected.getString(name);
            listed.add(name);
            if (!checksum.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("文件校验值无效");
            }
            String actual = scanned.hashes.get(name);
            if (actual != null) {
                if (!checksum.equals(actual)) {
                    throw new IllegalArgumentException("文件校验失败");
                }
            } else {
                if (!scanned.deferredAudio || !name.startsWith("audio/")) {
                    throw new IllegalArgumentException("文件校验失败");
                }
            }
        }
        Set<String> archive = new HashSet<>(scanned.names);
        archive.remove("checksums.json");
        if (!listed.equals(archive)) {
            throw new IllegalArgumentException("校验清单不完整");
        }
    }

    private static final class ScanResult {

        final Set<String> names;

        final Map<String, String> hashes;

        final boolean deferredAudio;

        ScanResult(Set<String> names, Map<String, String> hashes, boolean deferredAudio) {
            this.names = names;
            this.hashes = hashes;
            this.deferredAudio = deferredAudio;
        }
    }

    private static long entryLimit(String name) {
        if ("cover.webp".equals(name)) {
            return 8L * 1024 * 1024;
        }
        if (name.endsWith(".json")) {
            return 16L * 1024 * 1024;
        }
        return MAX_ENTRY;
    }

    private static boolean allowedFile(String path) {
        return path.equals("manifest.json")
                || path.equals("checksums.json")
                || path.equals("cover.webp")
                || path.matches("content/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")
                || path.matches("timeline/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")
                || path.matches("audio/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.m4a");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static void move(File from, File to) throws Exception {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(from.toPath(), to.toPath());
        }
    }

    public static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        file.delete();
    }

    public static final class AlreadyImportedException extends Exception {

        AlreadyImportedException(String title) {
            super("《" + title + "》已经在书库中");
        }
    }
}
