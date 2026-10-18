package com.canace.x3schedule.shared;

import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Wire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, versioned UI state. Restoring it never commits a database operation. */
final class ScreenSession {
    static final int MAX_CHARS = 24000;
    int schema = 1;
    String page = "home";
    int tab;
    int scroll;
    String calendarDay;
    int calendarMode;
    int calendarOffset;
    String editFrom;
    String editOccurrence;
    Plan plan;
    List<Plan> drafts = new ArrayList<>();
    String instanceId;
    com.canace.x3schedule.domain.Models.Instance calendarInstance;
    boolean pending;
    boolean importing;
    boolean reportRequested;
    boolean interrupted;
    Map<String, String> fields = new LinkedHashMap<>();
    Map<String, Integer> choices = new LinkedHashMap<>();

    String encode() {
        String encoded = Wire.JSON.toJson(this);
        if (encoded.length() <= MAX_CHARS) {
            return encoded;
        }
        ScreenSession fallback = new ScreenSession();
        fallback.tab = tab;
        fallback.interrupted = true;
        return Wire.JSON.toJson(fallback);
    }

    static ScreenSession decode(String encoded) {
        if (encoded == null) {
            return new ScreenSession();
        }
        try {
            if (encoded.length() > MAX_CHARS) {
                throw new IllegalArgumentException("Oversized UI state");
            }
            ScreenSession state = Wire.JSON.fromJson(encoded, ScreenSession.class);
            if (state == null || state.schema != 1 || state.page == null || state.tab < 0 || state.tab > 3
                    || state.calendarOffset < 0 || state.calendarOffset > 2000
                    || state.scroll < 0 || state.fields == null || state.choices == null || state.drafts == null) {
                throw new IllegalArgumentException("Invalid UI state");
            }
            return state;
        } catch (RuntimeException error) {
            ScreenSession fallback = new ScreenSession();
            fallback.interrupted = true;
            return fallback;
        }
    }
}
