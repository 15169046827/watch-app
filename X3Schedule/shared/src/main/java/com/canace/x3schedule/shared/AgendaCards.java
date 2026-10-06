package com.canace.x3schedule.shared;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import com.canace.x3schedule.domain.DayPrecision;
import com.canace.x3schedule.domain.Kinds;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.State;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class AgendaCards {
    private AgendaCards() {
    }

    static void item(LinearLayout parent, Instance item, HomeActions actions) {
        item(parent, item, actions, null);
    }

    static void item(LinearLayout parent, Instance item, HomeActions actions, State state) {
        String date = Instant.ofEpochMilli(item.start).atZone(ZoneId.of(item.zone))
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        if (item.dateOnly) {
            date = DayPrecision.day(item) + " · 全天";
        }
        String label = item.title + "\n" + date + " · " + Kinds.label(item.kind);
        if (state != null) {
            label += "\n" + stateLabel(state);
        }
        SpannableString styled = new SpannableString(label);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, item.title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Button button = ScheduleUi.button(parent, label, () -> actions.detail(item));
        button.setText(styled);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }

    static String stateLabel(State s) {
        switch (s) {
            case UPCOMING:
                return "待开始";
            case RUNNING:
                return "进行中";
            case PENDING:
                return "待确认";
            case OVERDUE:
                return "逾期未确认";
            case ON_TIME:
                return "按时完成";
            case LATE:
                return "延迟完成";
            case COMPLETED_UNKNOWN:
                return "已补录完成·时间未知";
            case INCOMPLETE:
                return "未完成";
            case LEAVE:
                return "请假";
            default:
                return Kinds.stateLabel(s);
        }
    }

    static void route(LinearLayout parent, String label, HomeActions.Route route, HomeActions actions) {
        ScheduleUi.button(parent, label, () -> actions.open(route));
    }
}
