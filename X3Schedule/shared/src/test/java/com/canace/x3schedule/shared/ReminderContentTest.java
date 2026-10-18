package com.canace.x3schedule.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Reminders.Purpose;
import org.junit.Test;

public class ReminderContentTest {
    @Test
    public void overdueStartAndEndShowOneConfirmationInsteadOfObsoleteStart() {
        ReminderContent content = new ReminderContent();
        Instance task = instance("learning", "学习", Kind.EXECUTION);
        content.add(task, Purpose.START, 200);
        content.add(task, Purpose.END, 200);
        content.add(task, Purpose.RETRY, 200);
        assertEquals(1, content.size());
        assertEquals("learning", content.instanceId());
        assertEquals("学习", content.title());
        assertEquals("学习 · 请确认完成情况", content.text());
    }

    @Test
    public void purposesDistinguishAdvanceStartDeadlineAndExplicitPostponement() {
        ReminderContent content = new ReminderContent();
        content.add(instance("start", "学习", Kind.EXECUTION), Purpose.START, 50);
        content.add(instance("meeting", "开会", Kind.REMINDER), Purpose.START, 100);
        content.add(instance("deadline", "提交报告", Kind.TODO), Purpose.START, 100);
        content.add(instance("continue", "阅读", Kind.EXECUTION), Purpose.CONFIRMATION, 100);
        assertNull(content.instanceId());
        assertEquals("学习 等 4 项日程", content.title());
        assertTrue(content.text().contains("学习 · 即将开始"));
        assertTrue(content.text().contains("开会 · 日程时间提醒"));
        assertTrue(content.text().contains("提交报告 · 待办到期提醒"));
        assertTrue(content.text().contains("阅读 · 延后时间已到，请确认完成情况"));
    }

    @Test
    public void largeBatchIsBoundedWithoutLosingTotalOrFirstDetails() {
        ReminderContent content = new ReminderContent();
        for (int i = 0; i < 100; i++) {
            content.add(instance("id" + i, "任务" + i, Kind.REMINDER), Purpose.START, 100);
        }
        assertEquals(100, content.size());
        assertEquals(7, content.text().split("\n").length);
        assertTrue(content.text().contains("任务0 · 日程时间提醒"));
        assertFalse(content.text().contains("任务6 ·"));
        assertTrue(content.text().endsWith("另有 94 项，点击查看"));
    }

    private static Instance instance(String id, String title, Kind kind) {
        Instance task = new Instance();
        task.id = id;
        task.title = title;
        task.kind = kind;
        task.start = 100;
        return task;
    }
}
