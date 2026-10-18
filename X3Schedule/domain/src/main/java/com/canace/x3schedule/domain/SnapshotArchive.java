package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** Bounded snapshot archive shared by backup and batches; never sent as one BLE frame. */
final class SnapshotArchive {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ITEMS = 50000;
    private static final int PAGE_ITEMS = 64;
    private static final byte[] MAGIC = "X3SCHEDULE-BACKUP/1\n".getBytes(StandardCharsets.US_ASCII);

    private SnapshotArchive() {
    }

    public static void validate(Wire.Snapshot snapshot) {
        Wire.validate(snapshot, MAX_ITEMS);
    }

    /** Does not close the caller's stream. A failed write is not a valid archive. */
    public static void write(Wire.Snapshot snapshot, OutputStream output) throws IOException {
        validate(snapshot);
        MessageDigest digest = sha256();
        DigestOutputStream checked = new DigestOutputStream(output, digest);
        DataOutputStream out = new DataOutputStream(checked);
        out.write(MAGIC);
        long size = MAGIC.length + 4L + 32;
        Wire.Snapshot header = header(snapshot);
        size = writePage(header, out, size);
        size = writePlans(snapshot, header, out, size);
        size = writeItems(snapshot.instances, header.instances, header, out, size);
        writeItems(snapshot.events, header.events, header, out, size);
        out.writeInt(0);
        checked.on(false);
        out.write(digest.digest());
        out.flush();
    }

    private static long writePlans(Wire.Snapshot snapshot, Wire.Snapshot page,
            DataOutputStream out, long size) throws IOException {
        for (Models.Plan plan : snapshot.plans) {
            page.plans.add(plan);
            size = writePage(page, out, size);
            page.plans.clear();
        }
        return size;
    }

    private static <T> long writeItems(List<T> source, List<T> target, Wire.Snapshot page,
            DataOutputStream out, long size) throws IOException {
        for (int offset = 0; offset < source.size(); offset += PAGE_ITEMS) {
            target.addAll(source.subList(offset, Math.min(offset + PAGE_ITEMS, source.size())));
            size = writePage(page, out, size);
            target.clear();
        }
        return size;
    }

    private static long writePage(Wire.Snapshot page, DataOutputStream out, long size) throws IOException {
        byte[] body = Wire.encode(page);
        long next = size + 4 + body.length;
        require(next <= MAX_BYTES, "备份超过 32 MiB，未完成导出");
        out.writeInt(body.length);
        out.write(body);
        return next;
    }

    /** Reads new archives or legacy JSON. No database mutation occurs during decoding. */
    public static Wire.Snapshot read(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(MAGIC.length);
        byte[] prefix = new byte[MAGIC.length];
        int count = 0;
        while (count < prefix.length) {
            int read = buffered.read(prefix, count, prefix.length - count);
            if (read == -1) {
                break;
            }
            count += read;
        }
        buffered.reset();
        if (count != MAGIC.length || !Arrays.equals(prefix, MAGIC)) {
            return readLegacy(buffered);
        }
        return readArchive(buffered, true);
    }

    private static Wire.Snapshot readLegacy(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] part = new byte[8192];
        int count;
        while ((count = in.read(part)) != -1) {
            require(bytes.size() + count <= Wire.MAX_BYTES, "旧 JSON 备份超过 2 MiB");
            bytes.write(part, 0, count);
        }
        Wire.Snapshot snapshot = Wire.decodeBackup(bytes.toByteArray());
        validate(snapshot);
        return snapshot;
    }

    static Wire.Snapshot readArchive(InputStream input) throws IOException {
        return readArchive(input, false);
    }

    private static Wire.Snapshot readArchive(InputStream input, boolean allowLegacy) throws IOException {
        MessageDigest digest = sha256();
        DigestInputStream checked = new DigestInputStream(input, digest);
        DataInputStream in = new DataInputStream(checked);
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        require(Arrays.equals(magic, MAGIC), "归档版本或格式无效");
        Wire.Snapshot result = null;
        String metadata = null;
        String format = null;
        long size = MAGIC.length + 4L + 32;
        int pages = 0;
        int length;
        while ((length = in.readInt()) != 0) {
            size += 4L + length;
            require(length > 0 && length <= Wire.MAX_BYTES && size <= MAX_BYTES && ++pages <= 4096,
                    "备份分块或总容量无效");
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            Wire.Snapshot original = Wire.JSON.fromJson(SnapshotInput.checkedText(bytes), Wire.Snapshot.class);
            String pageFormat = original.protocol + ":" + original.schema;
            require(format == null || format.equals(pageFormat), "备份分块版本不一致");
            format = pageFormat;
            Wire.Snapshot page = allowLegacy ? Wire.decodeBackup(bytes) : Wire.decode(bytes);
            if (result == null) {
                require(page.plans.isEmpty() && page.instances.isEmpty() && page.events.isEmpty(), "备份头无效");
                result = page;
                metadata = Wire.JSON.toJson(header(page));
            } else {
                require(metadata.equals(Wire.JSON.toJson(header(page))), "备份分块来源或版本不一致");
                append(result, page);
            }
        }
        checked.on(false);
        byte[] expected = new byte[32];
        in.readFully(expected);
        require(MessageDigest.isEqual(expected, digest.digest()) && in.read() == -1, "备份校验失败或含多余内容");
        validate(result);
        return result;
    }

    private static void append(Wire.Snapshot result, Wire.Snapshot page) {
        require(result.plans.size() + page.plans.size() <= 1000
                && result.instances.size() + page.instances.size() <= MAX_ITEMS
                && result.events.size() + page.events.size() <= MAX_ITEMS, "备份记录过多");
        result.plans.addAll(page.plans);
        result.instances.addAll(page.instances);
        result.events.addAll(page.events);
    }

    private static Wire.Snapshot header(Wire.Snapshot source) {
        Wire.Snapshot page = new Wire.Snapshot();
        page.protocol = source.protocol;
        page.schema = source.schema;
        page.id = source.id;
        page.source = source.source;
        page.deviceId = source.deviceId;
        page.settings = source.settings;
        page.settingsVersion = source.settingsVersion;
        page.revision = source.revision;
        return page;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
