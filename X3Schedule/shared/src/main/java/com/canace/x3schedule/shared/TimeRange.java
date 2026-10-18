package com.canace.x3schedule.shared;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** Clock end times at or before the start belong to the following day. */
final class TimeRange {
    private TimeRange() {
    }

    static int minute(String text) {
        LocalTime time;
        try {
            time = LocalTime.parse(text);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("请选择有效时间", error);
        }
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("时间须精确到分钟");
        }
        return time.getHour() * 60 + time.getMinute();
    }

    static int duration(String start, String end) {
        int minutes = minute(end) - minute(start);
        return minutes <= 0 ? minutes + 1440 : minutes;
    }
}
