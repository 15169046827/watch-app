package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.canace.x3schedule.domain.Models.Plan;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import org.junit.Test;

public class InputBoundaryTest {
    private Wire.Snapshot snapshot() {
        Wire.Snapshot snapshot = new Wire.Snapshot();
        snapshot.source = "phone";
        snapshot.deviceId = "synthetic";
        return snapshot;
    }

    private JsonObject document() {
        return Wire.JSON.toJsonTree(snapshot()).getAsJsonObject();
    }

    private Wire.Snapshot decode(String text) {
        return Wire.decode(text.getBytes(StandardCharsets.UTF_8));
    }

    private void rejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> decode(text));
    }

    private String event(String uid, String start, String end, String extra) {
        return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTART:" + start + "\r\nDTEND:" + end
                + "\r\nSUMMARY:合成事件\r\n" + extra + "END:VEVENT\r\n";
    }

    private String calendar(String events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events + "END:VCALENDAR\r\n";
    }

    @Test
    public void validBackupPreservesSegmentAndCategoryQuotas() {
        Wire.Snapshot original = snapshot();
        Plan plan = new Plan();
        plan.title = "合成计划";
        plan.anchor = "2026-09-01";
        plan.effectiveFrom = "2026-10-01";
        Plan prior = Wire.copy(plan, Plan.class);
        prior.effectiveFrom = "";
        prior.until = "2026-09-30";
        plan.previous.add(prior);
        original.plans.add(plan);
        original.settings.categoryWeek.put("学习", 2);
        original.settings.categoryMonth.put("学习", 6);
        Wire.Snapshot restored = Wire.decode(Wire.encode(original));
        assertEquals(Wire.JSON.toJson(original), Wire.JSON.toJson(restored));
    }

    @Test
    public void missingEnvelopeFieldsCannotBecomeConstructorDefaults() {
        String[] fields = { "protocol", "schema", "id", "source", "deviceId", "plans",
            "instances", "events", "settings", "settingsVersion", "revision" };
        for (String field : fields) {
            JsonObject json = document();
            json.remove(field);
            rejected(json.toString());
        }
    }

    @Test
    public void nullOrCoercedEnvelopeTypesAreRejected() {
        JsonObject json = document();
        json.addProperty("protocol", "1");
        rejected(json.toString());
        json = document();
        json.addProperty("source", 123);
        rejected(json.toString());
        rejected(document().toString().replace("\"protocol\":2", "\"protocol\":null"));
    }

    @Test
    public void duplicateRootAndNestedKeysAreRejected() {
        rejected(document().toString().replace("\"protocol\":2", "\"protocol\":2,\"protocol\":2"));
        rejected(document().toString().replace("\"categoryWeek\":{}", "\"categoryWeek\":{\"学习\":1,\"学习\":2}"));
    }

    @Test
    public void invalidUtf8IsRejectedBeforeReplacementCharactersCanBeStored() {
        byte[] bytes = document().toString().getBytes(StandardCharsets.UTF_8);
        int index = new String(bytes, StandardCharsets.UTF_8).indexOf("synthetic");
        bytes[index] = (byte) 0xc0;
        assertThrows(IllegalArgumentException.class, () -> Wire.decode(bytes));
    }

    @Test
    public void excessiveNestingIsRejectedWithoutStackOverflow() {
        String nested = "[".repeat(2000) + "0" + "]".repeat(2000);
        rejected(document().toString().replaceFirst("\\{", "{\"unknown\":" + nested + ","));
    }

    @Test
    public void shallowUnknownFieldsRemainForwardCompatible() {
        String text = document().toString().replaceFirst("\\{", "{\"unknown\":{\"values\":[1,true,null]},");
        assertEquals("synthetic", decode(text).deviceId);
    }

    @Test
    public void trailingDocumentsCommentsAndUnquotedNamesAreRejected() {
        rejected(document() + "{}");
        rejected(document().toString().replace("\"protocol\"", "protocol"));
        rejected(document().toString().replace("\"protocol\":2", "\"protocol\":/* comment */1"));
    }

    @Test
    public void nullAndNegativeCategoryQuotasAreRejected() {
        String text = document().toString();
        rejected(text.replace("\"categoryWeek\":{}", "\"categoryWeek\":null"));
        rejected(text.replace("\"categoryMonth\":{}", "\"categoryMonth\":{\"学习\":null}"));
        rejected(text.replace("\"categoryWeek\":{}", "\"categoryWeek\":{\"学习\":-1}"));
    }

    @Test
    public void invalidCategoryNamesAndExcessiveCountsAreRejected() {
        Wire.Snapshot snapshot = snapshot();
        snapshot.settings.categoryWeek.put(" ", 1);
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(snapshot));
        snapshot.settings.categoryWeek.clear();
        snapshot.settings.categoryWeek.put("x".repeat(81), 1);
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(snapshot));
        snapshot.settings.categoryWeek.clear();
        for (int i = 0; i <= 1000; i++) {
            snapshot.settings.categoryWeek.put("category" + i, 1);
        }
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(snapshot));
    }

    @Test
    public void invalidCalendarDateIsRejectedWhileLeapDayIsPreserved() {
        String invalid = calendar(event("a", "20260230T010000Z", "20260230T020000Z", ""));
        assertThrows(DateTimeParseException.class, () -> Ics.preview(invalid));
        String valid = calendar(event("a", "20240229T010000Z", "20240229T020000Z", ""));
        assertEquals("2024-02-29", Ics.preview(valid).get(0).anchor);
    }

    @Test
    public void secondsCannotBeSilentlyTruncated() {
        String start = calendar(event("a", "20260925T010001Z", "20260925T020000Z", ""));
        String end = calendar(event("a", "20260925T010000Z", "20260925T020001Z", ""));
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(start));
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(end));
    }

    @Test
    public void durationBeyondOneDayIsRejectedAndOneDayIsPreserved() {
        String invalid = calendar(event("a", "20260925T010000Z", "20260926T010100Z", ""));
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(invalid));
        String valid = calendar(event("a", "20260925T010000Z", "20260926T010000Z", ""));
        assertEquals(1440, Ics.preview(valid).get(0).durationMinutes);
    }

    @Test
    public void cancelledEventsAreNotRevivedByImport() {
        String text = calendar(event("a", "20260925T010000Z", "20260925T020000Z", "STATUS:CANCELLED\r\n"));
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(text));
    }

    @Test
    public void duplicateOrBlankUidCannotOverwriteAnImportedPlan() {
        String event = event("a", "20260925T010000Z", "20260925T020000Z", "");
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(calendar(event + event)));
        String blank = calendar(event(" ", "20260925T010000Z", "20260925T020000Z", ""));
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(blank));
        assertEquals(Ics.preview(calendar(event)).get(0).id, Ics.preview(calendar(event)).get(0).id);
    }
}
