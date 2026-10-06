package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Count the exact JSON UTF-8 representation without allocating a second complete payload. */
public final class Capacity {
    private Capacity() {
    }

    public static final class Usage {
        public final long bytes;
        public final int plans;
        public final int instances;
        public final int events;
        private final int maxBytes;
        private final int maxItems;

        Usage(long bytes, int plans, int instances, int events) {
            this(bytes, plans, instances, events, false);
        }

        Usage(long bytes, int plans, int instances, int events, boolean batch) {
            this.bytes = bytes;
            this.plans = plans;
            this.instances = instances;
            this.events = events;
            maxBytes = batch ? Batch.MAX_BYTES : Wire.MAX_BYTES;
            maxItems = batch ? Batch.MAX_ITEMS : Wire.MAX_ITEMS;
        }

        public boolean exceeded() {
            return bytes > maxBytes || plans > 1000 || instances > maxItems || events > maxItems;
        }

        public boolean nearLimit() {
            return bytes * 5 >= maxBytes * 4L || plans >= 800
                    || instances >= maxItems * 4 / 5 || events >= maxItems * 4 / 5;
        }

        public String status() {
            if (exceeded()) {
                return "同步数据已超上限，请查看数据容量";
            }
            return nearLimit() ? "同步数据已接近上限，请查看数据容量" : "同步数据容量正常";
        }
    }

    private static final class Counter extends OutputStream {
        long count;

        @Override
        public void write(int value) {
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            count += length;
        }
    }

    public static Usage measureBatch(Wire.Snapshot snapshot) {
        Batch.Manifest manifest = Batch.prepare(snapshot, bytes -> { });
        return new Usage(manifest.bytes, snapshot.plans.size(), snapshot.instances.size(), snapshot.events.size(),
                true);
    }

    public static Usage measure(Wire.Snapshot snapshot) {
        require(snapshot != null && snapshot.plans != null && snapshot.instances != null
                && snapshot.events != null, "无法读取容量");
        Counter counter = new Counter();
        try (OutputStreamWriter writer = new OutputStreamWriter(counter, StandardCharsets.UTF_8)) {
            Wire.JSON.toJson(snapshot, writer);
        } catch (IOException error) {
            throw new IllegalStateException("无法计算容量", error);
        }
        return new Usage(counter.count, snapshot.plans.size(), snapshot.instances.size(), snapshot.events.size());
    }
}
