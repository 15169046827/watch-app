package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Materialized UTC events keep Chinese roster semantics intact when exporting to other calendars.
 */
public final class Ics {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);

    private Ics() {
    }

    public static String export(List<Instance> instances) {
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Canace//Schedule 1//ZH\r\n");
        for (Instance i : instances) {
            if (i.cancelled) {
                continue;
            }
            require(i.end - i.start <= 86400000L, "ICS 暂不支持超过24小时的日程，请使用完整备份");
            require(!i.dateOnly, "ICS 暂不支持按天日程，请使用完整备份");
            require(i.kind != Kind.TODO, "ICS 不支持待办，请使用完整备份");
            line(out, "BEGIN:VEVENT");
            line(out, "UID:" + i.id + "@x3schedule");
            line(out, "DTSTAMP:" + UTC.format(Instant.ofEpochMilli(i.start)));
            line(out, "DTSTART:" + UTC.format(Instant.ofEpochMilli(i.start)));
            line(out, "DTEND:" + UTC.format(Instant.ofEpochMilli(i.end)));
            line(out, "SUMMARY:" + escape(i.title));
            line(out, "X-X3SCHEDULE-KIND:" + i.kind.name());
            line(out, "END:VEVENT");
        }
        out.append("END:VCALENDAR\r\n");
        require(out.toString().getBytes(StandardCharsets.UTF_8).length <= Wire.MAX_BYTES, "导出过大");
        return out.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,");
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        boolean slash = false;
        for (char c : s.toCharArray()) {
            if (slash) {
                b.append(c == 'n' || c == 'N' ? '\n' : c);
                slash = false;
            } else if (c == '\\') {
                slash = true;
            } else {
                b.append(c);
            }
        }
        require(!slash, "ICS 转义不完整");
        return b.toString();
    }

    private static void line(StringBuilder out, String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String v = new String(Character.toChars(cp));
            int n = v.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > 73) {
                out.append("\r\n ");
                bytes = 1;
            }
            out.append(v);
            bytes += n;
            i += Character.charCount(cp);
        }
        out.append("\r\n");
    }

    public static List<Plan> preview(String input) {
        require(input != null && input.getBytes(StandardCharsets.UTF_8).length <= Wire.MAX_BYTES, "ICS 过大");
        String unfolded = input.replaceAll("\\r?\\n[ \\t]", "");
        IcsInput.validate(unfolded);
        List<Plan> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, String> fields = null;
        for (String line : unfolded.split("\\r?\\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                require(fields == null, "ICS 嵌套事件无效");
                fields = new HashMap<>();
            } else if (line.equals("END:VEVENT")) {
                require(fields != null, "ICS 事件无效");
                Plan plan = toPlan(fields);
                require(ids.add(plan.id), "同一 ICS 文件中存在重复 UID");
                result.add(plan);
                fields = null;
                require(result.size() <= 1000, "ICS 事件过多");
            } else if (fields != null) {
                int colon = line.indexOf(':');
                require(colon > 0, "ICS 行无效");
                String key = line.substring(0, colon);
                require(!fields.containsKey(key), "重复 ICS 属性");
                fields.put(key, line.substring(colon + 1));
            }
        }
        require(fields == null && !result.isEmpty(), "ICS 没有完整事件");
        return result;
    }

    private static Plan toPlan(Map<String, String> f) {
        for (String k : f.keySet()) {
            require(!k.startsWith("RRULE") && !k.startsWith("RDATE") && !k.startsWith("EXDATE") && !k
                    .startsWith("RECURRENCE-ID") && !k.startsWith("DURATION") && !k.startsWith("BEGIN") && !k
                    .startsWith("DTSTART;") && !k.startsWith("DTEND;"), "第一阶段仅导入 UTC 单次事件；周期/全天/浮动时区请先展开");
        }
        require(f.containsKey("DTSTART") && f.containsKey("DTEND") && f.containsKey("SUMMARY") && f
                .containsKey("UID"), "ICS 缺少时间、名称或 UID");
        require(!"CANCELLED".equalsIgnoreCase(f.get("STATUS")), "已取消的 ICS 事件不能导入为活动日程");
        require(!f.get("UID").trim().isEmpty(), "ICS UID 不能为空");
        Instant start = Instant.from(UTC.parse(f.get("DTSTART")));
        Instant end = Instant.from(UTC.parse(f.get("DTEND")));
        require(start.getEpochSecond() % 60 == 0 && end.getEpochSecond() % 60 == 0,
                "当前日程精确到分钟；不能无损导入含秒的 ICS 事件");
        require(!end.isBefore(start) && Duration.between(start, end).getSeconds() <= 86400, "ICS 时长无效");
        Plan p = new Plan();
        p.kind = Kind.valueOf(f.getOrDefault("X-X3SCHEDULE-KIND", "REMINDER"));
        require(p.kind != Kind.TODO, "ICS 不支持待办，请使用完整备份");
        p.id = UUID.nameUUIDFromBytes(f.get("UID").getBytes(StandardCharsets.UTF_8)).toString();
        ZonedDateTime z = start.atZone(ZoneId.of(p.zone));
        p.anchor = z.toLocalDate().toString();
        p.rule = Rule.DAYS;
        p.count = 1;
        p.startMinute = z.getHour() * 60 + z.getMinute();
        p.durationMinutes = (int) Duration.between(start, end).toMinutes();
        p.title = unescape(f.get("SUMMARY"));
        p.validate();
        return p;
    }
}
