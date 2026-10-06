package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Plan;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately bounded local grammar. Returns a draft only; callers must preview before saving.
 */
public final class NaturalLanguage {

    private static final Pattern PATTERN = Pattern
            .compile("^每周([一二三四五六日天]+)(?:早上|上午|下午|晚上)?\\s*(\\d{1,2})[:：](\\d{2})"
                    + "\\s*(?:到|至|-)\\s*(\\d{1,2})[:：](\\d{2})\\s+(.+)$");

    private NaturalLanguage() {
    }

    public static Plan preview(String text, LocalDate anchor) {
        require(text != null && text.length() <= 240, "请输入简单周期指令");
        Matcher m = PATTERN.matcher(text.trim());
        require(m.matches(), "支持示例：每周一三五早上 6:30 到 7:30 晨跑；其他规则请使用表单");
        Plan p = new Plan();
        p.inheritReminders = true;
        p.anchor = anchor.toString();
        p.weekdays = 0;
        for (char c : m.group(1).toCharArray()) {
            int n = "一二三四五六日".indexOf(c);
            if (c == '天') {
                n = 6;
            }
            p.weekdays |= 1 << n;
        }
        int h = Integer.parseInt(m.group(2));
        int min = Integer.parseInt(m.group(3));
        int eh = Integer.parseInt(m.group(4));
        int em = Integer.parseInt(m.group(5));
        require(h < 24 && eh < 24 && min < 60 && em < 60, "时间无效");
        // Only the grammar prefix describes the time period; words in the title are literal content.
        String period = m.group().substring(0, m.start(2));
        if (period.contains("下午") || period.contains("晚上")) {
            require(h <= 12 && eh <= 12, "时段和小时有歧义，请使用表单");
            if (h < 12) {
                h += 12;
            }
            if (eh < 12) {
                eh += 12;
            }
        }
        p.startMinute = h * 60 + min;
        int end = eh * 60 + em;
        p.durationMinutes = end - p.startMinute;
        if (p.durationMinutes < 0) {
            p.durationMinutes += 1440;
        }
        p.title = m.group(6).trim();
        p.validate();
        return p;
    }
}
