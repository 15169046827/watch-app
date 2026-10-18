package com.canace.x3schedule.domain;

import com.canace.x3schedule.domain.Models.Instance;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Prepared off the UI thread; dense overlap groups retain every event in a drill-down list. */
public final class CalendarDay {
    public final List<Instance> items;
    public final List<Instance> allDay = new ArrayList<>();
    public final List<Block> blocks = new ArrayList<>();

    public CalendarDay(List<Instance> source, LocalDate day, ZoneId zone, int width) {
        items = CalendarWindow.onDay(source, day, zone);
        for (Instance item : items) {
            if (item.dateOnly || item.progress != null && item.progress.untimed) {
                allDay.add(item);
            }
        }
        if (width > 0) {
            prepare(CalendarWindow.slots(items, day, zone), width);
        }
    }

    public static final class Block {
        public final List<Instance> items = new ArrayList<>();
        public final int start;
        public int end;
        public int column;
        public int columns = 1;

        private Block(CalendarWindow.Slot slot) {
            start = slot.start;
            end = slot.end;
            items.add(slot.item);
        }
    }

    private void prepare(List<CalendarWindow.Slot> slots, int width) {
        Block dense = null;
        for (CalendarWindow.Slot slot : slots) {
            if (width / slot.columns < 82) {
                if (dense == null || slot.start >= dense.end) {
                    dense = new Block(slot);
                    blocks.add(dense);
                } else {
                    dense.items.add(slot.item);
                    dense.end = Math.max(dense.end, slot.end);
                }
            } else {
                dense = null;
                Block block = new Block(slot);
                block.column = slot.column;
                block.columns = slot.columns;
                blocks.add(block);
            }
        }
    }
}
