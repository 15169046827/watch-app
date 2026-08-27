package com.canace.x3voiceledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LedgerParser {
    private static final Pattern ARABIC_WITH_UNIT = Pattern.compile("(?:[￥¥]\\s*)?(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块钱|块|圆)");
    private static final Pattern ARABIC_NUMBER = Pattern.compile("(?:[￥¥]\\s*)?(\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern CHINESE_WITH_UNIT = Pattern.compile("([零〇一二两三四五六七八九十百千万点]+)(?:元|块钱|块|圆)([零〇一二两三四五六七八九]{1,2})?(?:角|毛)?");
    private static final Pattern SPOKEN_TIME = Pattern.compile("(?:(凌晨|早上|上午|中午|下午|晚上))?([零〇一二两三四五六七八九十\\d]{1,3})(?:点|时)(?:([零〇一二两三四五六七八九十\\d]{1,3})分?)?");
    private static final Pattern EXPLICIT_LOCATION = Pattern.compile("(?:地点|地址)\\s*(?:是|在|为)?\\s*[:：]?\\s*([^，。,.!?！？]+)");
    private static final Pattern CONTEXT_LOCATION = Pattern.compile("(?:^|[，,。\\s])在([^，。,.!?！？]{2,30}?)(?=(?:吃|买|喝|消费|花|支付|付款|打车|加油|看))");

    static Result parse(String spokenText) {
        String raw = normalize(spokenText);
        if (raw.isEmpty()) {
            return Result.error("请先说出或输入一笔账单");
        }

        Match amountMatch = findAmount(raw);
        if (amountMatch == null || amountMatch.amountCents <= 0) {
            return Result.error("没有识别到金额，请例如说“午饭25元”");
        }
        if (amountMatch.amountCents > 99_999_999L) {
            return Result.error("金额过大，请重新输入");
        }

        long transactionTime = transactionTime(raw, System.currentTimeMillis());
        LocationMatch locationMatch = findLocation(raw);
        StringBuilder cleaned = new StringBuilder(raw);
        blank(cleaned, amountMatch.start, amountMatch.end);
        if (locationMatch != null) blank(cleaned, locationMatch.start, locationMatch.end);
        String note = cleaned.toString()
                .replaceAll("[，,。.!！?？:：]", " ")
                .replaceAll("(今天|昨天|前天)", " ")
                .replaceAll(SPOKEN_TIME.pattern(), " ")
                .replaceAll("^\\s*(花了|消费|支付|付款|支出|买了|买)\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (note.isEmpty()) {
            note = "日常消费";
        }
        return Result.success(amountMatch.amountCents, note, categoryFor(note), raw, transactionTime,
                locationMatch == null ? "" : locationMatch.label);
    }

    static long parseAmountText(String text) {
        Match match = findAmount(normalize(text));
        return match == null ? -1L : match.amountCents;
    }

    private static long transactionTime(String text, long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        if (text.contains("前天")) calendar.add(Calendar.DAY_OF_YEAR, -2);
        else if (text.contains("昨天")) calendar.add(Calendar.DAY_OF_YEAR, -1);

        Matcher time = SPOKEN_TIME.matcher(text);
        if (time.find()) {
            int hour = flexibleInteger(time.group(2));
            int minute = time.group(3) == null ? 0 : flexibleInteger(time.group(3));
            String period = time.group(1);
            if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
            if ("中午".equals(period) && hour < 11) hour += 12;
            if ("凌晨".equals(period) && hour == 12) hour = 0;
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                calendar.set(Calendar.HOUR_OF_DAY, hour);
                calendar.set(Calendar.MINUTE, minute);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
            }
        }
        return calendar.getTimeInMillis();
    }

    private static int flexibleInteger(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return (int) chineseInteger(value);
        }
    }

    private static Match findAmount(String text) {
        Matcher matcher = ARABIC_WITH_UNIT.matcher(text);
        if (matcher.find()) {
            return decimalMatch(matcher, matcher.group(1));
        }

        matcher = CHINESE_WITH_UNIT.matcher(text);
        if (matcher.find()) {
            String main = matcher.group(1);
            String tail = matcher.group(2);
            double value;
            int point = main.indexOf('点');
            if (point >= 0) {
                value = chineseInteger(main.substring(0, point)) + chineseFraction(main.substring(point + 1));
            } else {
                value = chineseInteger(main);
            }
            if (tail != null && !tail.isEmpty()) {
                value += chineseFraction(tail);
            }
            return new Match(matcher.start(), matcher.end(), Math.round(value * 100d));
        }

        matcher = ARABIC_NUMBER.matcher(text);
        if (matcher.find()) {
            return decimalMatch(matcher, matcher.group(1));
        }
        return null;
    }

    private static LocationMatch findLocation(String text) {
        Matcher explicit = EXPLICIT_LOCATION.matcher(text);
        if (explicit.find()) {
            return new LocationMatch(explicit.start(), explicit.end(), explicit.group(1).trim());
        }
        Matcher contextual = CONTEXT_LOCATION.matcher(text);
        if (contextual.find()) {
            return new LocationMatch(contextual.start(), contextual.end(), contextual.group(1).trim());
        }
        return null;
    }

    private static void blank(StringBuilder value, int start, int end) {
        for (int i = start; i < end; i++) value.setCharAt(i, ' ');
    }

    private static Match decimalMatch(Matcher matcher, String value) {
        try {
            long cents = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2).longValueExact();
            return new Match(matcher.start(), matcher.end(), cents);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static double chineseFraction(String value) {
        double factor = 0.1d;
        double result = 0d;
        for (int i = 0; i < value.length() && i < 2; i++) {
            result += digit(value.charAt(i)) * factor;
            factor /= 10d;
        }
        return result;
    }

    private static long chineseInteger(String value) {
        if (value.isEmpty()) return 0;
        long total = 0;
        long section = 0;
        int number = 0;
        boolean sawUnit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int unit = unit(c);
            if (unit == 10 || unit == 100 || unit == 1000) {
                sawUnit = true;
                if (number == 0) number = 1;
                section += (long) number * unit;
                number = 0;
            } else if (unit == 10000) {
                sawUnit = true;
                section += number;
                total += section * 10000L;
                section = 0;
                number = 0;
            } else {
                int d = digit(c);
                number = sawUnit ? d : number * 10 + d;
            }
        }
        return total + section + number;
    }

    private static int digit(char c) {
        switch (c) {
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return 0;
        }
    }

    private static int unit(char c) {
        switch (c) {
            case '十': return 10;
            case '百': return 100;
            case '千': return 1000;
            case '万': return 10000;
            default: return 0;
        }
    }

    private static String categoryFor(String note) {
        if (note.matches(".*(工资|奖金|收入|收款|进账|退款|报销|补贴|兼职|理财盈利|中奖).*")) return "收入";
        if (note.matches(".*(饭|餐|面|粉|咖啡|奶茶|早餐|午餐|晚餐|外卖|水果|菜).*")) return "餐饮";
        if (note.matches(".*(地铁|公交|打车|出租|滴滴|车费|加油|停车|火车|机票).*")) return "交通";
        if (note.matches(".*(药|医院|门诊|挂号|体检).*")) return "医疗";
        if (note.matches(".*(电影|游戏|唱歌|旅游|门票).*")) return "娱乐";
        if (note.matches(".*(买|商店|超市|衣服|鞋|日用品).*")) return "购物";
        return "其他";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '０' && c <= '９') c = (char) ('0' + c - '０');
            else if (c == '．') c = '.';
            result.append(c);
        }
        return result.toString().trim();
    }

    static String formatAmount(long cents) {
        return String.format(Locale.CHINA, "¥%.2f", cents / 100d);
    }

    private static final class Match {
        final int start;
        final int end;
        final long amountCents;

        Match(int start, int end, long amountCents) {
            this.start = start;
            this.end = end;
            this.amountCents = amountCents;
        }
    }

    private static final class LocationMatch {
        final int start;
        final int end;
        final String label;

        LocationMatch(int start, int end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    static final class Result {
        final boolean valid;
        final long amountCents;
        final String note;
        final String category;
        final String rawText;
        final long transactionTime;
        final String locationLabel;
        final String error;

        private Result(boolean valid, long amountCents, String note, String category,
                       String rawText, long transactionTime, String locationLabel, String error) {
            this.valid = valid;
            this.amountCents = amountCents;
            this.note = note;
            this.category = category;
            this.rawText = rawText;
            this.transactionTime = transactionTime;
            this.locationLabel = locationLabel;
            this.error = error;
        }

        static Result success(long amountCents, String note, String category, String rawText,
                              long transactionTime, String locationLabel) {
            return new Result(true, amountCents, note, category, rawText, transactionTime,
                    locationLabel, null);
        }

        static Result error(String message) {
            return new Result(false, 0, null, null, null, 0L, null, message);
        }
    }
}
