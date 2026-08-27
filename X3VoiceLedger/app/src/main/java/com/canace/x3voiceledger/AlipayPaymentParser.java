package com.canace.x3voiceledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for the confirmed Alipay watch NFC success-page node contract. */
final class AlipayPaymentParser {
    private static final String SUCCESS_ID = "nfc_pay_text";
    private static final String AMOUNT_ID = "tv_amount";
    private static final Pattern AMOUNT = Pattern.compile("(?:[￥¥]\\s*)?(\\d{1,9}(?:\\.\\d{1,2})?)");

    static Result parse(List<Node> nodes) {
        if (nodes == null) return null;
        boolean success = false;
        String amountText = null;
        for (Node node : nodes) {
            if (node == null) continue;
            if (matchesId(node.viewId, SUCCESS_ID) && normalize(node.text).contains("付款成功")) {
                success = true;
            }
            // Deliberately exact: never fall back to success_money_reduce_amount or other money nodes.
            if (matchesId(node.viewId, AMOUNT_ID)) amountText = node.text;
        }
        if (!success || amountText == null) return null;
        long cents = parseCents(amountText);
        return cents > 0 ? new Result(cents) : null;
    }

    private static long parseCents(String value) {
        Matcher matcher = AMOUNT.matcher(normalize(value));
        if (!matcher.find()) return -1L;
        try {
            BigDecimal amount = new BigDecimal(matcher.group(1)).setScale(2, RoundingMode.UNNECESSARY);
            long cents = amount.movePointRight(2).longValueExact();
            return cents <= 99_999_999L ? cents : -1L;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean matchesId(String value, String id) {
        if (value == null) return false;
        return value.equals(id) || value.endsWith(":id/" + id) || value.endsWith("/" + id);
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

    static final class Node {
        final String viewId;
        final String text;

        Node(String viewId, String text) {
            this.viewId = viewId;
            this.text = text;
        }
    }

    static final class Result {
        final long amountCents;

        Result(long amountCents) {
            this.amountCents = amountCents;
        }
    }

    private AlipayPaymentParser() { }
}
