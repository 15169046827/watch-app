package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import java.util.HashSet;
import java.util.Set;

/** Validate the supported VCALENDAR/VEVENT container before importing any event. */
final class IcsInput {
    private final Set<String> rootKeys = new HashSet<>();
    private int state;
    private int events;

    private IcsInput() {
    }

    static void validate(String unfolded) {
        IcsInput input = new IcsInput();
        for (String line : unfolded.split("\\r?\\n")) {
            input.line(line);
        }
        require(input.state == 3 && input.events > 0, "ICS 容器不完整或没有事件");
    }

    private void line(String line) {
        if (line.isEmpty()) {
            return;
        }
        switch (line) {
            case "BEGIN:VCALENDAR":
                require(state == 0, "只支持一个完整 ICS 日历");
                state = 1;
                break;
            case "END:VCALENDAR":
                require(state == 1 && rootKeys.contains("VERSION"), "ICS 日历结束位置无效");
                state = 3;
                break;
            case "BEGIN:VEVENT":
                require(state == 1 && rootKeys.contains("VERSION"), "ICS 事件位置无效");
                state = 2;
                break;
            case "END:VEVENT":
                require(state == 2, "ICS 事件结束位置无效");
                state = 1;
                events++;
                break;
            default:
                property(line);
                break;
        }
    }

    private void property(String line) {
        require(state == 1 || state == 2, "ICS 容器外存在内容");
        int colon = line.indexOf(':');
        require(colon > 0, "ICS 属性无效");
        String key = line.substring(0, colon);
        String value = line.substring(colon + 1);
        require(!key.equals("BEGIN") && !key.equals("END"), "不支持嵌套组件、时区或待办，请先展开事件");
        if (state == 2) {
            return;
        }
        require(rootKeys.add(key), "重复 ICS 日历属性");
        if (key.equals("VERSION")) {
            require(value.equals("2.0"), "仅支持 ICS 2.0");
        } else if (key.equals("METHOD")) {
            require(value.equals("PUBLISH"), "不支持邀请或取消消息");
        } else if (key.equals("CALSCALE")) {
            require(value.equals("GREGORIAN"), "不支持其他历法");
        }
    }
}
