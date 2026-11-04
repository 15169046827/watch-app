package com.canace.x3offlinereader.playback;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Performs one volume request and, if warranted, one system UI retry. */
final class MediaVolumeRequest {
    private MediaVolumeRequest() { }

    static void apply(int target, IntSupplier read, IntConsumer write, IntConsumer showSystemUi) {
        int before = read.getAsInt();
        if (target == before) {
            return;
        }
        write.accept(target);
        int actual = read.getAsInt();
        if (target > before && actual < target) {
            showSystemUi.accept(target);
        }
    }
}
