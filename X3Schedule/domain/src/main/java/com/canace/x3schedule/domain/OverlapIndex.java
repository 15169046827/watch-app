package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Instance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counts conflicts against other plans without comparing every pair on the UI thread. */
public final class OverlapIndex {
    private final Bounds all;
    private final Map<String, Bounds> plans = new HashMap<>();

    public OverlapIndex(List<Instance> items) {
        List<Instance> timed = new ArrayList<>();
        Map<String, List<Instance>> groups = new HashMap<>();
        for (Instance item : items) {
            if (Progress.occupiesTime(item)) {
                timed.add(item);
                groups.computeIfAbsent(item.planId, ignored -> new ArrayList<>()).add(item);
            }
        }
        all = new Bounds(timed);
        groups.forEach((id, group) -> plans.put(id, new Bounds(group)));
    }

    public int count(Instance item) {
        if (!Progress.occupiesTime(item)) {
            return 0;
        }
        Bounds own = plans.get(item.planId);
        return all.count(item) - (own == null ? 0 : own.count(item));
    }

    private static final class Bounds {
        private final long[] starts;
        private final long[] ends;

        Bounds(List<Instance> items) {
            starts = new long[items.size()];
            ends = new long[items.size()];
            for (int n = 0; n < items.size(); n++) {
                Instance item = items.get(n);
                starts[n] = item.start;
                ends[n] = Math.max(item.end, item.start + 1);
            }
            Arrays.sort(starts);
            Arrays.sort(ends);
        }

        int count(Instance item) {
            return before(starts, Math.max(item.end, item.start + 1), false) - before(ends, item.start, true);
        }

        private static int before(long[] values, long boundary, boolean inclusive) {
            int low = 0;
            int high = values.length;
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (values[middle] < boundary || (inclusive && values[middle] == boundary)) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }
}
