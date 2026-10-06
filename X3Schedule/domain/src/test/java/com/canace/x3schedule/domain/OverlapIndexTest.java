package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;

import com.canace.x3schedule.domain.Models.Instance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public final class OverlapIndexTest {
    private Instance item(String id, String plan, long start, long end) {
        Instance item = new Instance();
        item.id = id;
        item.planId = plan;
        item.start = start;
        item.end = end;
        return item;
    }

    @Test
    public void touchingEndpointsDoNotConflictAndPointsUseOneMillisecond() {
        Instance hidden = item("cancel", "a", 0, 100);
        hidden.cancelled = true;
        Instance untimed = item("todo", "a", 0, 100);
        untimed.timed = false;
        OverlapIndex index = new OverlapIndex(Arrays.asList(item("a", "a", 10, 20),
                item("b", "b", 20, 20), item("c", "c", 20, 30), hidden, untimed));
        assertEquals(1, index.count(item("q", "new", 19, 20)));
        assertEquals(2, index.count(item("q", "new", 20, 20)));
        assertEquals(1, index.count(item("q", "c", 20, 20)));
        assertEquals(0, index.count(item("q", "new", 30, 40)));
        assertEquals(0, index.count(hidden));
        assertEquals(0, index.count(untimed));
    }

    @Test
    public void indexedCountsMatchPairwiseRulesAcrossRandomIntervalsAndPlans() {
        Random random = new Random(20261002);
        List<Instance> saved = new ArrayList<>();
        for (int n = 0; n < 1000; n++) {
            long start = random.nextInt(1000);
            Instance item = item("saved-" + n, "plan-" + n % 17, start, start + random.nextInt(100));
            item.cancelled = n % 23 == 0;
            item.timed = n % 31 != 0;
            saved.add(item);
        }
        OverlapIndex index = new OverlapIndex(saved);
        for (int n = 0; n < 1000; n++) {
            long start = random.nextInt(1200);
            Instance query = item("new-" + n, "plan-" + n % 19, start, start + random.nextInt(200));
            int expected = 0;
            for (Instance old : saved) {
                if (!query.planId.equals(old.planId) && Recurrence.overlaps(query, old)) {
                    expected++;
                }
            }
            assertEquals(expected, index.count(query));
        }
    }
}
