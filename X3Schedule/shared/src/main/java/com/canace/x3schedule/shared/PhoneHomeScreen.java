package com.canace.x3schedule.shared;

import android.widget.LinearLayout;
import com.canace.x3schedule.domain.Agenda;
import com.canace.x3schedule.domain.Models.Instance;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Phone navigation and information hierarchy are independent of the watch home. */
final class PhoneHomeScreen {
    private PhoneHomeScreen() {
    }

    static void today(LinearLayout content, Agenda agenda, HomeActions actions) {
        ScheduleUi.text(content, LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · EEEE")), 13);
        ScheduleUi.primary(ScheduleUi.button(content, "＋ 新建日程", () -> actions.open(HomeActions.Route.NEW)));
        ScheduleUi.heading(content, "当前与下一项");
        if (agenda.current != null) {
            AgendaCards.item(content, agenda.current, actions, agenda.states.get(agenda.current.id),
                    agenda.progress.get(agenda.current.id));
        }
        if (agenda.next != null) {
            AgendaCards.item(content, agenda.next, actions, agenda.states.get(agenda.next.id),
                    agenda.progress.get(agenda.next.id));
        }
        if (agenda.current == null && agenda.next == null) {
            ScheduleUi.text(content, "暂时没有要开始的日程。给接下来的时间留一个安排吧。", 15);
        }
        ScheduleUi.heading(content, "需要处理");
        AgendaCards.route(content, "待确认 · " + agenda.pending + " 项", HomeActions.Route.PENDING, actions);
        AgendaCards.route(content, "待办清单 · " + agenda.todos + " 项未完成", HomeActions.Route.TODOS, actions);
        ScheduleUi.heading(content, "今日日程");
        int shown = 0;
        for (Instance item : agenda.today) {
            if (shown++ < 8) {
                AgendaCards.item(content, item, actions, agenda.states.get(item.id), agenda.progress.get(item.id));
            }
        }
        if (agenda.today.isEmpty()) {
            ScheduleUi.text(content, "今天暂无已保存的日程。", 15);
        }
        AgendaCards.route(content, "查看今日与下一项", HomeActions.Route.TODAY, actions);
        ScheduleUi.text(content, "今日范围按本机时区；每项时间按日程时区显示。", 12);
    }

    static void plans(LinearLayout content, HomeActions actions) {
        ScheduleUi.text(content, "为日程选择合适的用途。记录执行、到时提醒，或做完勾选。", 15);
        ScheduleUi.primary(ScheduleUi.button(content, "＋ 新建日程", () -> actions.open(HomeActions.Route.NEW)));
        ScheduleUi.heading(content, "我的安排");
        AgendaCards.route(content, "管理日程", HomeActions.Route.PLANS, actions);
        AgendaCards.route(content, "待办清单", HomeActions.Route.TODOS, actions);
        AgendaCards.route(content, "待确认事项", HomeActions.Route.PENDING, actions);
        AgendaCards.route(content, "今天与下一项", HomeActions.Route.TODAY, actions);
        ScheduleUi.heading(content, "快速录入");
        AgendaCards.route(content, "自然语言预览", HomeActions.Route.NATURAL, actions);
        ScheduleUi.text(content, "先预览并确认，再保存到本机。", 13);
    }

    static void settings(LinearLayout content, HomeActions actions) {
        ScheduleUi.heading(content, "设备与提醒");
        AgendaCards.route(content, "连接与权限", HomeActions.Route.CONNECTION, actions);
        AgendaCards.route(content, "提醒与请假额度", HomeActions.Route.REMINDERS, actions);
        ScheduleUi.heading(content, "数据管理");
        AgendaCards.route(content, "备份与 ICS", HomeActions.Route.FILES, actions);
        AgendaCards.route(content, "数据容量", HomeActions.Route.CAPACITY, actions);
        AgendaCards.route(content, "隐私与本机数据", HomeActions.Route.PRIVACY, actions);
        ScheduleUi.text(content, "日常工具 · 日程\n日程与记录保存在本机，通过已配对设备同步。", 13);
    }
}
