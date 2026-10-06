package com.canace.x3schedule.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.Random;
import org.junit.Test;

public class FramesTest {

    @Test
    public void fragmentedAtEveryByte() {
        byte[] p = new byte[513];
        new Random(1).nextBytes(p);
        byte[] f = Frames.pack(1, p);
        Frames r = new Frames();
        for (int i = 0; i < f.length - 1; i++) {
            assertNull(r.add(new byte[] { f[i] }));
        }
        assertArrayEquals(p, Frames.body(r.add(new byte[] { f[f.length - 1] })));
    }

    @Test
    public void malformedLengthRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Frames().add(new byte[] { 1, 127, 0, 0, 0 }));
    }

    @Test
    public void repeatedIndependentFrames() {
        Frames r = new Frames();
        byte[] p = Frames.pack(2, new byte[] { 3 });
        assertArrayEquals(p, r.add(p));
        assertArrayEquals(p, r.add(p));
    }
}
