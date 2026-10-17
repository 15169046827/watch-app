package com.canace.x3schedule.shared;

import android.widget.LinearLayout;
import com.canace.x3schedule.domain.Agenda;
import com.canace.x3schedule.domain.Models.Kind;

/** Compact round-screen home; complex planning remains on the phone. */
final class WatchHomeScreen {
    private WatchHomeScreen() {
    }

    static void show(LinearLayout content, Agenda agenda, HomeActions actions) {
        ScheduleUi.heading(content, "当前日程");
        if (agenda.current == null) {
            ScheduleUi.text(content, "当前没有进行中的日程", 14);
        } else {
            AgendaCards.item(content, agenda.current, actions);
            if (agenda.current.kind == Kind.EXECUTION) {
                ScheduleUi.primary(ScheduleUi.button(content, "刚刚完成",
                        () -> {
                            if (agenda.current.progress == null) {
                                actions.complete(agenda.current);
                            } else {
                                actions.detail(agenda.current);
                            }
                        }));
            }
        }
        ScheduleUi.heading(content, "下一项");
        if (agenda.next == null) {
            ScheduleUi.text(content, "暂无下一项\n可在手机创建并同步", 14);
        } else {
            AgendaCards.item(content, agenda.next, actions);
        }
        ScheduleUi.heading(content, "日程清单");
        AgendaCards.route(content, "待确认 · " + agenda.pending, HomeActions.Route.PENDING, actions);
        AgendaCards.route(content, "待办 · " + agenda.todos + " 项未完成", HomeActions.Route.TODOS, actions);
        AgendaCards.route(content, "今日与下一项", HomeActions.Route.TODAY, actions);
        ScheduleUi.heading(content, "设备");
        AgendaCards.route(content, "连接与权限", HomeActions.Route.CONNECTION, actions);
        AgendaCards.route(content, "数据容量", HomeActions.Route.CAPACITY, actions);
        AgendaCards.route(content, "隐私与本机数据", HomeActions.Route.PRIVACY, actions);
    }
}
