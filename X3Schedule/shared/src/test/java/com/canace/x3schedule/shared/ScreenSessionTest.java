package com.canace.x3schedule.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import org.junit.Test;

public final class ScreenSessionTest {
    @Test
    public void calendarAndSingleOccurrenceDraftKeepSelectionAndScope() {
        ScreenSession state = new ScreenSession();
        state.page = "editor";
        state.calendarDay = "2026-11-02";
        state.calendarMode = 1;
        state.calendarOffset = 480;
        state.editOccurrence = "2026-10-30";
        state.plan = new Plan();
        state.plan.anchor = "2026-11-02";
        state.plan.durationMinutes = 1950;
        state.fields.put("日程名称", "还未保存的合成日程");
        ScreenSession restored = ScreenSession.decode(state.encode());
        assertEquals("2026-11-02", restored.calendarDay);
        assertEquals(1, restored.calendarMode);
        assertEquals(480, restored.calendarOffset);
        assertEquals("2026-10-30", restored.editOccurrence);
        assertEquals(1950, restored.plan.durationMinutes);
        assertEquals(state.fields, restored.fields);
        assertTrue(restored.drafts.isEmpty());
    }

    @Test
    public void invalidDraftTextSurvivesWithoutDomainValidation() {
        ScreenSession state = new ScreenSession();
        state.page = "editor";
        state.plan = new Plan();
        state.plan.kind = Kind.TODO;
        state.fields.put("名称", "  尚未完成的输入 😀\n第二行  ");
        state.fields.put("截止时间（可空，例 2026-10-01T13:00）", "2026-");
        state.choices.put("提醒端", 2);
        state.scroll = 123;
        ScreenSession restored = ScreenSession.decode(state.encode());
        assertEquals(state.plan.id, restored.plan.id);
        assertEquals(Kind.TODO, restored.plan.kind);
        assertEquals(state.fields, restored.fields);
        assertEquals(state.choices, restored.choices);
        assertEquals(123, restored.scroll);
        assertFalse(restored.interrupted);
    }

    @Test
    public void previewKeepsIdentityAndImportFlag() {
        ScreenSession state = new ScreenSession();
        state.page = "preview";
        state.importing = true;
        Plan plan = new Plan();
        plan.title = "合成预览";
        state.drafts.add(plan);
        ScreenSession restored = ScreenSession.decode(state.encode());
        assertEquals("preview", restored.page);
        assertTrue(restored.importing);
        assertEquals(plan.id, restored.drafts.get(0).id);
        assertEquals(plan.title, restored.drafts.get(0).title);
        assertEquals(1, restored.drafts.size());
    }

    @Test
    public void reportRetainsFiltersAndRequestIntent() {
        ScreenSession state = new ScreenSession();
        state.tab = 2;
        state.reportRequested = true;
        state.fields.put("自定义开始日期", "2026-09-01");
        state.fields.put("大类名称（空为全部）", "运动");
        state.choices.put("报告周期", 4);
        ScreenSession restored = ScreenSession.decode(state.encode());
        assertEquals(2, restored.tab);
        assertTrue(restored.reportRequested);
        assertEquals(state.fields, restored.fields);
        assertEquals(Integer.valueOf(4), restored.choices.get("报告周期"));
    }

    @Test
    public void oversizedStateFallsBackWithoutPartiallyRestoringDraft() {
        ScreenSession state = new ScreenSession();
        state.page = "editor";
        state.tab = 1;
        state.plan = new Plan();
        state.fields.put("名称", "日".repeat(ScreenSession.MAX_CHARS));
        String encoded = state.encode();
        assertTrue(encoded.length() <= ScreenSession.MAX_CHARS);
        ScreenSession restored = ScreenSession.decode(encoded);
        assertTrue(restored.interrupted);
        assertEquals("home", restored.page);
        assertEquals(1, restored.tab);
        assertNull(restored.plan);
        assertTrue(restored.fields.isEmpty());
    }

    @Test
    public void hugePreviewAlsoUsesSmallFallback() {
        ScreenSession state = new ScreenSession();
        state.page = "preview";
        Plan plan = new Plan();
        plan.title = "x".repeat(ScreenSession.MAX_CHARS);
        state.drafts.add(plan);
        ScreenSession restored = ScreenSession.decode(state.encode());
        assertTrue(restored.interrupted);
        assertTrue(restored.drafts.isEmpty());
    }

    @Test
    public void malformedOrFutureStateReturnsExplicitFallback() {
        String[] invalid = { "null", "{", "[]", "{\"schema\":2}", "{\"tab\":4}", "{\"tab\":-1}",
            "{\"scroll\":-1}", "{\"calendarOffset\":-1}", "{\"calendarOffset\":2001}",
            "{\"page\":null}", "{\"fields\":null}", "{\"choices\":null}",
            "{\"drafts\":null}", "x".repeat(ScreenSession.MAX_CHARS + 1) };
        for (String encoded : invalid) {
            ScreenSession restored = ScreenSession.decode(encoded);
            assertTrue(restored.interrupted);
            assertEquals("home", restored.page);
        }
    }

    @Test
    public void missingStateIsANormalFreshLaunch() {
        ScreenSession restored = ScreenSession.decode(null);
        assertFalse(restored.interrupted);
        assertEquals("home", restored.page);
        assertEquals(0, restored.tab);
    }

}
