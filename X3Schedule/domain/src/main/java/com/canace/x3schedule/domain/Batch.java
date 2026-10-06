package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Protocol 3 separates durable page receipts from atomic whole-snapshot commit receipts. */
public final class Batch {
    public static final int PAGE_BYTES = 128 * 1024;
    public static final int MAX_BYTES = SnapshotArchive.MAX_BYTES;
    public static final int MAX_ITEMS = SnapshotArchive.MAX_ITEMS;
    public static final int MAX_PACKET = PAGE_BYTES + 512;

    private Batch() {
    }

    public static class Manifest {
        public String id;
        public String source;
        public String deviceId;
        public long revision;
        public int count;
        public int bytes;
        public String digest;

        public void validate() {
            identity(id);
            identity(deviceId);
            require("phone".equals(source) || "watch".equals(source), "批次来源无效");
            require(revision >= 0 && bytes > 0 && bytes <= MAX_BYTES
                    && count == (bytes + PAGE_BYTES - 1) / PAGE_BYTES, "批次容量无效");
            require(digest != null && digest.matches("[a-f0-9]{64}"), "批次摘要无效");
        }
    }

    public static class Part {
        public Manifest manifest;
        public int index;
        public byte[] payload;
    }

    public static class Ack {
        public String id;
        public int index;
        public String digest;
        public boolean committed;
    }

    public static Manifest prepare(Wire.Snapshot snapshot, Consumer<byte[]> sink) {
        Manifest result = new Manifest();
        result.id = snapshot.id;
        result.source = snapshot.source;
        result.deviceId = snapshot.deviceId;
        result.revision = snapshot.revision;
        MessageDigest digest = sha256();
        Pages pages = new Pages(sink);
        try {
            SnapshotArchive.write(snapshot, new DigestOutputStream(pages, digest));
            pages.finish();
        } catch (IOException error) {
            throw new IllegalArgumentException("同步批次生成失败", error);
        }
        result.bytes = pages.total;
        result.count = (result.bytes + PAGE_BYTES - 1) / PAGE_BYTES;
        result.digest = hex(digest.digest());
        result.validate();
        return result;
    }

    private static final class Pages extends OutputStream {
        private final Consumer<byte[]> sink;
        private final byte[] buffer = new byte[PAGE_BYTES];
        private int used;
        private int total;

        Pages(Consumer<byte[]> sink) {
            this.sink = sink;
        }

        @Override
        public void write(int value) {
            buffer[used++] = (byte) value;
            total++;
            require(total <= MAX_BYTES, "同步批次超过 32 MiB");
            if (used == PAGE_BYTES) {
                finish();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            while (length > 0) {
                int size = Math.min(PAGE_BYTES - used, length);
                System.arraycopy(bytes, offset, buffer, used, size);
                used += size;
                total += size;
                require(total <= MAX_BYTES, "同步批次超过 32 MiB");
                offset += size;
                length -= size;
                if (used == PAGE_BYTES) {
                    finish();
                }
            }
        }

        void finish() {
            if (used > 0) {
                sink.accept(Arrays.copyOf(buffer, used));
                used = 0;
            }
        }
    }

    public static byte[] packet(Manifest manifest, int index, byte[] payload) {
        checkPart(manifest, index, payload);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(3);
            out.writeUTF(manifest.id);
            out.writeUTF(manifest.source);
            out.writeUTF(manifest.deviceId);
            out.writeLong(manifest.revision);
            out.writeInt(manifest.count);
            out.writeInt(manifest.bytes);
            out.writeUTF(manifest.digest);
            out.writeInt(index);
            out.write(payload);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    public static Part decode(byte[] packet) {
        require(packet != null && packet.length <= MAX_PACKET, "分批数据过大");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet));
            require(in.readInt() == 3, "请将两端升级到 0.5.0 或兼容版本");
            Part part = new Part();
            Manifest m = new Manifest();
            m.id = in.readUTF();
            m.source = in.readUTF();
            m.deviceId = in.readUTF();
            m.revision = in.readLong();
            m.count = in.readInt();
            m.bytes = in.readInt();
            m.digest = in.readUTF();
            part.manifest = m;
            part.index = in.readInt();
            part.payload = new byte[in.available()];
            in.readFully(part.payload);
            checkPart(m, part.index, part.payload);
            return part;
        } catch (IOException error) {
            throw new IllegalArgumentException("分批数据格式无效", error);
        }
    }

    private static void checkPart(Manifest manifest, int index, byte[] payload) {
        manifest.validate();
        require(index >= 0 && index < manifest.count, "分批序号无效");
        int expected = index == manifest.count - 1 ? manifest.bytes - index * PAGE_BYTES : PAGE_BYTES;
        require(payload != null && payload.length == expected, "分批长度无效");
    }

    public static Wire.Snapshot assemble(Manifest manifest, IntFunction<byte[]> parts) {
        manifest.validate();
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(new PartsInput(manifest, parts), digest)) {
            Wire.Snapshot snapshot = SnapshotArchive.readArchive(in);
            require(hex(digest.digest()).equals(manifest.digest), "整批数据校验失败");
            require(manifest.id.equals(snapshot.id) && manifest.source.equals(snapshot.source)
                    && manifest.deviceId.equals(snapshot.deviceId) && manifest.revision == snapshot.revision,
                    "整批元数据不一致");
            return snapshot;
        } catch (IOException error) {
            throw new IllegalArgumentException("整批数据不完整", error);
        }
    }

    private static final class PartsInput extends InputStream {
        private final Manifest manifest;
        private final IntFunction<byte[]> parts;
        private int next;
        private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);

        PartsInput(Manifest manifest, IntFunction<byte[]> parts) {
            this.manifest = manifest;
            this.parts = parts;
        }

        @Override
        public int read() {
            int value = current.read();
            while (value == -1 && next < manifest.count) {
                byte[] body = parts.apply(next);
                checkPart(manifest, next++, body);
                current = new ByteArrayInputStream(body);
                value = current.read();
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first == -1) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int rest = current.read(bytes, offset + 1, length - 1);
            return 1 + Math.max(0, rest);
        }
    }

    public static Ack ack(Part part, byte[] packet, boolean committed) {
        Ack ack = new Ack();
        ack.id = part.manifest.id;
        ack.index = part.index;
        ack.digest = Wire.digest(packet);
        ack.committed = committed;
        return ack;
    }

    public static int next(Manifest manifest, int index, byte[] packet, Ack ack) {
        manifest.validate();
        require(index >= 0 && index < manifest.count, "发送序号无效");
        require(ack != null && manifest.id.equals(ack.id) && index == ack.index
                && Wire.digest(packet).equals(ack.digest), "分批回执不匹配");
        require(!ack.committed || index == manifest.count - 1, "整批确认过早");
        if (ack.committed) {
            return manifest.count;
        }
        return index == manifest.count - 1 ? 0 : index + 1;
    }

    public static byte[] encodeAck(Ack ack) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(3);
            out.writeUTF(ack.id);
            out.writeInt(ack.index);
            out.writeUTF(ack.digest);
            out.writeBoolean(ack.committed);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    public static Ack decodeAck(byte[] bytes) {
        require(bytes != null && bytes.length <= 256, "回执过大");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            require(in.readInt() == 3, "回执版本无效");
            Ack ack = new Ack();
            ack.id = in.readUTF();
            identity(ack.id);
            ack.index = in.readInt();
            ack.digest = in.readUTF();
            int flag = in.readUnsignedByte();
            require(ack.index >= 0 && ack.index < 256 && ack.digest.matches("[a-f0-9]{64}")
                    && flag <= 1 && in.read() == -1, "回执格式无效");
            ack.committed = flag == 1;
            return ack;
        } catch (IOException error) {
            throw new IllegalArgumentException("回执格式无效", error);
        }
    }

    private static void identity(String value) {
        require(value != null && value.matches("[A-Za-z0-9_-]{1,80}"), "批次标识无效");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) {
            value.append(String.format(Locale.ROOT, "%02x", b & 255));
        }
        return value.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
