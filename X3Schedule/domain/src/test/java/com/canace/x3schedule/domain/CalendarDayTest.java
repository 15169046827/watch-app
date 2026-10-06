package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Instance;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class CalendarDayTest {
    private final LocalDate day = LocalDate.parse("2026-10-02");
    private final ZoneId zone = ZoneId.of("UTC");

    private Instance item(String id, int start, int end) {
        Instance item = new Instance();
        item.id = id;
        item.zone = zone.getId();
        item.start = day.atStartOfDay(zone).plusMinutes(start).toInstant().toEpochMilli();
        item.end = day.atStartOfDay(zone).plusMinutes(end).toInstant().toEpochMilli();
        return item;
    }

    @Test
    public void denseChainsKeepEveryEventAndSeparateLaterGroups() {
        List<Instance> source = Arrays.asList(item("a", 540, 660), item("b", 600, 720),
                item("c", 660, 780), item("d", 840, 900));
        CalendarDay result = new CalendarDay(source, day, zone, 140);
        assertEquals(2, result.blocks.size());
        assertEquals(3, result.blocks.get(0).items.size());
        assertEquals(540, result.blocks.get(0).start);
        assertEquals(780, result.blocks.get(0).end);
        assertEquals("d", result.blocks.get(1).items.get(0).id);
        assertEquals(1, result.blocks.get(0).columns);
    }

    @Test
    public void readableColumnsStayIndividualAndReuseReleasedColumn() {
        List<Instance> source = Arrays.asList(item("a", 540, 660), item("b", 600, 720),
                item("c", 660, 780));
        CalendarDay result = new CalendarDay(source, day, zone, 220);
        assertEquals(3, result.blocks.size());
        assertEquals(0, result.blocks.get(0).column);
        assertEquals(1, result.blocks.get(1).column);
        assertEquals(0, result.blocks.get(2).column);
        for (CalendarDay.Block block : result.blocks) {
            assertTrue(220 / block.columns - 2 >= 80);
            assertEquals(1, block.items.size());
        }
    }

    @Test
    public void fiftyThousandOverlapsProduceOneTouchableBlockWithoutDroppingItems() {
        List<Instance> source = new ArrayList<>();
        for (int n = 0; n < 50000; n++) {
            source.add(item("event-" + n, 540, 600));
        }
        CalendarDay result = new CalendarDay(source, day, zone, 140);
        assertEquals(1, result.blocks.size());
        Set<String> ids = new HashSet<>();
        for (Instance item : result.blocks.get(0).items) {
            assertTrue(ids.add(item.id));
        }
        assertEquals(50000, ids.size());
        assertEquals(1, result.blocks.get(0).columns);
    }

    @Test
    public void monthPreparationSkipsLayoutAndAllDayStaysSeparate() {
        Instance allDay = item("all", 0, 1440);
        allDay.dateOnly = true;
        List<Instance> source = Arrays.asList(allDay, item("timed", 540, 600));
        CalendarDay month = new CalendarDay(source, day, zone, 0);
        assertEquals(2, month.items.size());
        assertTrue(month.blocks.isEmpty());
        assertEquals(1, month.allDay.size());
        CalendarDay timeline = new CalendarDay(source, day, zone, 140);
        assertEquals(1, timeline.blocks.size());
        assertEquals("timed", timeline.blocks.get(0).items.get(0).id);
    }
}
