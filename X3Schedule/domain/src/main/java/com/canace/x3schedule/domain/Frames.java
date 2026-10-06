package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Length-prefix framing; one bounded allocation and linear copying per frame.
 */
public final class Frames {

    private final byte[] header = new byte[5];

    private int headerSize;

    private int position;

    private byte[] frame;

    public static byte[] pack(int kind, byte[] body) {
        require(body.length <= limit(kind), "Frame too large");
        return ByteBuffer.allocate(body.length + 5).put((byte) kind).putInt(body.length).put(body).array();
    }

    public byte[] add(byte[] chunk) {
        require(chunk.length <= 512, "Chunk too large");
        int offset = 0;
        if (frame == null) {
            int n = Math.min(chunk.length, 5 - headerSize);
            System.arraycopy(chunk, 0, header, headerSize, n);
            headerSize += n;
            offset = n;
            if (headerSize < 5) {
                return null;
            }
            int length = ByteBuffer.wrap(header, 1, 4).getInt();
            require(length >= 0 && length <= limit(header[0]), "Frame length invalid");
            frame = new byte[length + 5];
            System.arraycopy(header, 0, frame, 0, 5);
            position = 5;
        }
        int n = chunk.length - offset;
        require(position + n <= frame.length, "Frame overflow");
        System.arraycopy(chunk, offset, frame, position, n);
        position += n;
        if (position == frame.length) {
            byte[] complete = frame;
            frame = null;
            position = 0;
            headerSize = 0;
            return complete;
        }
        return null;
    }

    private static int limit(int kind) {
        require(kind >= 1 && kind <= 5, "Frame kind invalid");
        if (kind == 3) {
            return Batch.MAX_PACKET;
        }
        if (kind == 4) {
            return 256;
        }
        return kind == 5 ? 0 : Wire.MAX_BYTES;
    }

    public static byte[] body(byte[] frame) {
        return Arrays.copyOfRange(frame, 5, frame.length);
    }
}
