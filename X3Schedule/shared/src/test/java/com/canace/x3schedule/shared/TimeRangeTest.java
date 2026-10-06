package com.canace.x3schedule.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class TimeRangeTest {
    @Test
    public void daytimeEndCalculatesDuration() {
        assertEquals(90, TimeRange.duration("09:00", "10:30"));
    }

    @Test
    public void earlierEndMeansFollowingDay() {
        assertEquals(90, TimeRange.duration("23:30", "01:00"));
    }

    @Test
    public void equalClocksMeanOneDayWhenEndIsExplicit() {
        assertEquals(1440, TimeRange.duration("09:00", "09:00"));
    }

    @Test
    public void minutePrecisionRejectsSeconds() {
        assertThrows(IllegalArgumentException.class, () -> TimeRange.minute("09:00:01"));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.minute("09:00:00.001"));
    }

    @Test
    public void invalidClockIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TimeRange.duration("25:00", "09:00"));
    }

    @Test
    public void clockBoundariesAreExactMinutes() {
        assertEquals(0, TimeRange.minute("00:00"));
        assertEquals(1439, TimeRange.minute("23:59"));
    }
}
