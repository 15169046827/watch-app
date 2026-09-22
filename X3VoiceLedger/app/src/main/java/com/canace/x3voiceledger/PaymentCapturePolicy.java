package com.canace.x3voiceledger;

final class PaymentCapturePolicy {
    static final String LIMITATIONS =
            "仅支持已确认的支付宝 NFC 付款成功页，必须同时识别“付款成功”和精确金额字段。"
                    + "可靠字段只有金额、支付宝渠道和事件时间；商户、分类与账户需要补充。"
                    + "部分支付可能不出现可读金额而漏记，语音或手工录入仍是可靠后备。";

    private PaymentCapturePolicy() { }

    static boolean shouldCapture(
            boolean userConsented,
            boolean fromAlipay,
            boolean successWindowActive,
            boolean capturedCurrentWindow) {
        return userConsented && fromAlipay && successWindowActive && !capturedCurrentWindow;
    }
}