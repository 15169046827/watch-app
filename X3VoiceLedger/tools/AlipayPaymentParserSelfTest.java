package com.canace.x3voiceledger;

import java.util.Arrays;

public final class AlipayPaymentParserSelfTest {
    public static void main(String[] args) {
        AlipayPaymentParser.Result normal =
                AlipayPaymentParser.parse(
                        Arrays.asList(
                                new AlipayPaymentParser.Node(
                                        "com.eg.android.AlipayGphone:id/nfc_pay_text", "付款成功"),
                                new AlipayPaymentParser.Node(
                                        "com.eg.android.AlipayGphone:id/success_money_reduce_amount",
                                        "88.76"),
                                new AlipayPaymentParser.Node(
                                        "com.eg.android.AlipayGphone:id/tv_amount", "38.76")));
        require(normal != null && normal.amountCents == 3876, "must prefer tv_amount");

        AlipayPaymentParser.Result fullWidth =
                AlipayPaymentParser.parse(
                        Arrays.asList(
                                new AlipayPaymentParser.Node("nfc_pay_text", "付款成功"),
                                new AlipayPaymentParser.Node("tv_amount", "￥１２．３０")));
        require(fullWidth != null && fullWidth.amountCents == 1230, "full-width amount");

        require(
                AlipayPaymentParser.parse(
                                Arrays.asList(
                                        new AlipayPaymentParser.Node("nfc_pay_text", "付款成功"),
                                        new AlipayPaymentParser.Node(
                                                "success_money_reduce_amount", "1.00")))
                        == null,
                "discount-only page must not parse");
        require(
                AlipayPaymentParser.parse(
                                Arrays.asList(new AlipayPaymentParser.Node("tv_amount", "18.00")))
                        == null,
                "amount without success must not parse");
        System.out.println("AlipayPaymentParserSelfTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
