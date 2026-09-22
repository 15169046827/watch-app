package com.canace.x3voiceledger;

import java.util.Calendar;

public final class ParserSelfTest {
    public static void main(String[] args) {
        expect("午饭25元", 2500, "午饭", "餐饮");
        expect("打车六块五", 650, "打车", "交通");
        expect("咖啡18.5", 1850, "咖啡", "餐饮");
        expect("买水果二十五元", 2500, "水果", "餐饮");
        expect("地铁１２．５元", 1250, "地铁", "交通");
        expect("工资5000元", 500000, "工资", "收入");
        expectTime("昨天下午三点咖啡25元", 15, "咖啡");
        expectLocation("午饭25元，地点是大众家园一号楼", "午饭", "大众家园一号楼");
        expectLocation("在万象城买咖啡18元", "咖啡", "万象城");
        if (LedgerParser.parseAmountText("¥88.60") != 8860) {
            throw new AssertionError("editable amount parsing failed");
        }
        if (LedgerParser.parse("只说内容没有金额").valid) {
            throw new AssertionError("missing amount must fail");
        }
        System.out.println("ParserSelfTest: all cases passed");
    }

    private static void expectLocation(String input, String note, String location) {
        LedgerParser.Result result = LedgerParser.parse(input);
        if (!result.valid || !note.equals(result.note) || !location.equals(result.locationLabel)) {
            throw new AssertionError(
                    input + " => note=" + result.note + ", location=" + result.locationLabel);
        }
    }

    private static void expectTime(String input, int hour, String note) {
        Calendar expected = Calendar.getInstance();
        expected.add(Calendar.DAY_OF_YEAR, -1);
        LedgerParser.Result result = LedgerParser.parse(input);
        Calendar actual = Calendar.getInstance();
        actual.setTimeInMillis(result.transactionTime);
        if (!result.valid
                || actual.get(Calendar.YEAR) != expected.get(Calendar.YEAR)
                || actual.get(Calendar.DAY_OF_YEAR) != expected.get(Calendar.DAY_OF_YEAR)
                || actual.get(Calendar.HOUR_OF_DAY) != hour
                || !note.equals(result.note)) {
            throw new AssertionError("spoken transaction time parsing failed");
        }
    }

    private static void expect(String input, long cents, String note, String category) {
        LedgerParser.Result result = LedgerParser.parse(input);
        if (!result.valid
                || result.amountCents != cents
                || !note.equals(result.note)
                || !category.equals(result.category)) {
            throw new AssertionError(
                    input
                            + " => valid="
                            + result.valid
                            + ", cents="
                            + result.amountCents
                            + ", note="
                            + result.note
                            + ", category="
                            + result.category);
        }
    }
}
