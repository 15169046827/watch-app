package com.canace.x3offlinereader.importer;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Small read-only ZIP central-directory reader that works directly on seekable SAF descriptors. */
final class SeekableZipArchive implements AutoCloseable {

    private static final int EOCD = 0x06054b50;

    private static final int ZIP64_LOCATOR = 0x07064b50;

    private static final int ZIP64_EOCD = 0x06064b50;

    private static final int CENTRAL = 0x02014b50;

    private static final int LOCAL = 0x04034b50;

    private static final long UINT32 = 0xffff_ffffL;

    private static final int MAX_ENTRIES = 8192;

    private static final int MAX_CENTRAL = 64 * 1024 * 1024;

    private static final Charset CP437 = Charset.forName("CP437");

    private final FileInputStream input;

    private final FileChannel channel;

    private final List<Entry> entries;

    private final Map<String, Entry> byName;

    static SeekableZipArchive open(File file) throws Exception {
        return new SeekableZipArchive(new FileInputStream(file));
    }

    static SeekableZipArchive open(FileInputStream input) throws Exception {
        return new SeekableZipArchive(input);
    }

    private SeekableZipArchive(FileInputStream input) throws Exception {
        this.input = input;
        channel = input.getChannel();
        List<Entry> parsed;
        try {
            parsed = parse();
        } catch (Exception failure) {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            throw failure;
        }
        entries = Collections.unmodifiableList(parsed);
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (index.put(entry.name, entry) != null) {
                throw new IllegalArgumentException("压缩包存在重复路径");
            }
        }
        byName = Collections.unmodifiableMap(index);
    }

    List<Entry> entries() {
        return entries;
    }

    Entry getEntry(String name) {
        return byName.get(name);
    }

    InputStream open(Entry entry) throws Exception {
        ByteBuffer local = read(entry.localOffset, 30);
        if (local.getInt(0) != LOCAL) {
            throw new IllegalArgumentException("ZIP 本地文件头无效");
        }
        int flags = u16(local, 6);
        int method = u16(local, 8);
        int nameLength = u16(local, 26);
        int extraLength = u16(local, 28);
        if ((flags & 1) != 0 || flags != entry.flags || method != entry.method) {
            throw new IllegalArgumentException("不支持的 ZIP 条目");
        }
        long dataOffset = checkedAdd(entry.localOffset, 30L + nameLength + extraLength);
        InputStream raw = new SliceInputStream(channel, dataOffset, entry.compressedSize);
        if (method == 0) {
            return raw;
        }
        if (method == 8) {
            return new InflaterInputStream(raw, new Inflater(true), 64 * 1024);
        }
        throw new IllegalArgumentException("不支持的 ZIP 压缩方式");
    }

    private List<Entry> parse() throws Exception {
        Eocd eocd = findEocd();
        Directory directory = directory(eocd);
        validateDirectory(directory, eocd.offset);
        List<Entry> result = new ArrayList<>((int) directory.entryCount);
        long cursor = directory.offset;
        for (long index = 0; index < directory.entryCount; index++) {
            ParsedEntry parsed = entry(cursor);
            result.add(parsed.entry);
            cursor = parsed.nextOffset;
        }
        if (cursor > checkedAdd(directory.offset, directory.size)) {
            throw new IllegalArgumentException("ZIP 中央目录大小无效");
        }
        return result;
    }

    private Eocd findEocd() throws Exception {
        long length = channel.size();
        if (length < 22) {
            throw new IllegalArgumentException("ZIP 文件过短");
        }
        int tailLength = (int) Math.min(length, 22L + 65535L);
        long tailOffset = length - tailLength;
        ByteBuffer tail = read(tailOffset, tailLength);
        int index = locateEocd(tail, tailLength);
        if (index < 0) {
            throw new IllegalArgumentException("ZIP 中央目录缺失");
        }
        return new Eocd(tail, index, tailOffset + index);
    }

    private static int locateEocd(ByteBuffer tail, int length) {
        for (int index = length - 22; index >= 0; index--) {
            if (tail.getInt(index) == EOCD && index + 22 + u16(tail, index + 20) == length) {
                return index;
            }
        }
        return -1;
    }

    private Directory directory(Eocd eocd) throws Exception {
        long entryCount = u16(eocd.data, eocd.index + 10);
        long centralSize = u32(eocd.data, eocd.index + 12);
        long centralOffset = u32(eocd.data, eocd.index + 16);
        if (entryCount == 0xffff || centralSize == UINT32 || centralOffset == UINT32) {
            return zip64Directory(eocd.offset);
        }
        return new Directory(entryCount, centralSize, centralOffset);
    }

    private Directory zip64Directory(long eocdOffset) throws Exception {
        if (eocdOffset < 20) {
            throw new IllegalArgumentException("ZIP64 定位器缺失");
        }
        ByteBuffer locator = read(eocdOffset - 20, 20);
        if (locator.getInt(0) != ZIP64_LOCATOR) {
            throw new IllegalArgumentException("ZIP64 定位器无效");
        }
        ByteBuffer record = read(locator.getLong(8), 56);
        if (record.getInt(0) != ZIP64_EOCD) {
            throw new IllegalArgumentException("ZIP64 中央目录无效");
        }
        return new Directory(record.getLong(32), record.getLong(40), record.getLong(48));
    }

    private static void validateDirectory(Directory directory, long eocdOffset) {
        if (directory.entryCount < 0
                || directory.entryCount > MAX_ENTRIES
                || directory.size < 0
                || directory.size > MAX_CENTRAL
                || directory.offset < 0
                || checkedAdd(directory.offset, directory.size) > eocdOffset) {
            throw new IllegalArgumentException("ZIP 中央目录超出限制");
        }
    }

    private ParsedEntry entry(long cursor) throws Exception {
        ByteBuffer fixed = read(cursor, 46);
        if (fixed.getInt(0) != CENTRAL) {
            throw new IllegalArgumentException("ZIP 中央目录条目无效");
        }
        int flags = u16(fixed, 8);
        int method = u16(fixed, 10);
        validateEntryFormat(flags, method);
        int nameLength = u16(fixed, 28);
        int extraLength = u16(fixed, 30);
        int commentLength = u16(fixed, 32);
        long compressed = u32(fixed, 20);
        long size = u32(fixed, 24);
        long localOffset = u32(fixed, 42);
        byte[] nameBytes = bytes(checkedAdd(cursor, 46), nameLength);
        String name =
                new String(nameBytes, (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : CP437);
        long[] values =
                expandZip64(cursor, nameLength, extraLength, size, compressed, localOffset);
        validateEntryValues(values);
        long next = checkedAdd(cursor, 46L + nameLength + extraLength + commentLength);
        return new ParsedEntry(
                new Entry(name, values[0], values[1], method, flags, values[2]), next);
    }

    private static void validateEntryFormat(int flags, int method) {
        if ((flags & 1) != 0 || (method != 0 && method != 8)) {
            throw new IllegalArgumentException("不支持的 ZIP 条目");
        }
    }

    private long[] expandZip64(
            long cursor,
            int nameLength,
            int extraLength,
            long size,
            long compressed,
            long localOffset)
            throws Exception {
        long[] result = {size, compressed, localOffset};
        if (size != UINT32 && compressed != UINT32 && localOffset != UINT32) {
            return result;
        }
        long[] extended =
                zip64(
                        bytes(checkedAdd(cursor, 46L + nameLength), extraLength),
                        size == UINT32,
                        compressed == UINT32,
                        localOffset == UINT32);
        int index = 0;
        if (size == UINT32) {
            result[0] = extended[index++];
        }
        if (compressed == UINT32) {
            result[1] = extended[index++];
        }
        if (localOffset == UINT32) {
            result[2] = extended[index];
        }
        return result;
    }

    private static void validateEntryValues(long[] values) {
        if (values[0] < 0 || values[1] < 0 || values[2] < 0) {
            throw new IllegalArgumentException("ZIP64 条目无效");
        }
    }

    private static final class Eocd {

        final ByteBuffer data;

        final int index;

        final long offset;

        Eocd(ByteBuffer data, int index, long offset) {
            this.data = data;
            this.index = index;
            this.offset = offset;
        }
    }

    private static final class Directory {

        final long entryCount;

        final long size;

        final long offset;

        Directory(long entryCount, long size, long offset) {
            this.entryCount = entryCount;
            this.size = size;
            this.offset = offset;
        }
    }

    private static final class ParsedEntry {

        final Entry entry;

        final long nextOffset;

        ParsedEntry(Entry entry, long nextOffset) {
            this.entry = entry;
            this.nextOffset = nextOffset;
        }
    }
    private static long[] zip64(byte[] extra, boolean size, boolean compressed, boolean offset) {
        ByteBuffer data = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        while (data.remaining() >= 4) {
            int id = Short.toUnsignedInt(data.getShort());
            int length = Short.toUnsignedInt(data.getShort());
            if (length > data.remaining()) {
                break;
            }
            if (id == 1) {
                ByteBuffer value = data.slice().order(ByteOrder.LITTLE_ENDIAN);
                int needed = (size ? 1 : 0) + (compressed ? 1 : 0) + (offset ? 1 : 0);
                if (value.remaining() < needed * 8) {
                    throw new IllegalArgumentException("ZIP64 条目数据不完整");
                }
                long[] result = new long[needed];
                for (int i = 0; i < needed; i++) {
                    result[i] = value.getLong();
                }
                return result;
            }
            data.position(data.position() + length);
        }
        throw new IllegalArgumentException("ZIP64 条目数据缺失");
    }

    private byte[] bytes(long offset, int length) throws Exception {
        ByteBuffer value = read(offset, length);
        byte[] bytes = new byte[length];
        value.get(bytes);
        return bytes;
    }

    private ByteBuffer read(long offset, int length) throws Exception {
        if (offset < 0 || length < 0 || checkedAdd(offset, length) > channel.size()) {
            throw new EOFException();
        }
        ByteBuffer value = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long position = offset;
        while (value.hasRemaining()) {
            int count = channel.read(value, position);
            if (count < 0) {
                throw new EOFException();
            }
            if (count == 0) {
                throw new IOException("ZIP 随机读取无进展");
            }
            position += count;
        }
        value.flip();
        return value;
    }

    private static int u16(ByteBuffer value, int offset) {
        return Short.toUnsignedInt(value.getShort(offset));
    }

    private static long u32(ByteBuffer value, int offset) {
        return Integer.toUnsignedLong(value.getInt(offset));
    }

    private static long checkedAdd(long left, long right) {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            throw new IllegalArgumentException("ZIP 偏移无效");
        }
        return left + right;
    }

    @Override
    public void close() throws Exception {
        input.close();
    }

    static final class Entry {

        final String name;

        final long size;

        final long compressedSize;

        final int method;

        final int flags;

        final long localOffset;

        Entry(
                String name,
                long size,
                long compressedSize,
                int method,
                int flags,
                long localOffset) {
            this.name = name;
            this.size = size;
            this.compressedSize = compressedSize;
            this.method = method;
            this.flags = flags;
            this.localOffset = localOffset;
        }
    }

    private static final class SliceInputStream extends InputStream {

        private final FileChannel channel;

        private long position;

        private long remaining;

        SliceInputStream(FileChannel channel, long position, long remaining) {
            this.channel = channel;
            this.position = position;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int wanted = (int) Math.min(length, remaining);
            ByteBuffer target = ByteBuffer.wrap(buffer, offset, wanted);
            int count = channel.read(target, position);
            if (count < 0) {
                throw new EOFException();
            }
            if (count == 0) {
                return 0;
            }
            position += count;
            remaining -= count;
            return count;
        }

        @Override
        public long skip(long count) {
            long skipped = Math.min(Math.max(0, count), remaining);
            position += skipped;
            remaining -= skipped;
            return skipped;
        }
    }
}
