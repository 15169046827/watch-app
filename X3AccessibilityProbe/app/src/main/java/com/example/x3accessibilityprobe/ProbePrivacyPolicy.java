package com.example.x3accessibilityprobe;

import java.util.List;
import java.util.Locale;

final class ProbePrivacyPolicy {

    enum ScheduleAction {
        DROP,
        KEEP_PENDING,
        REPLACE_PENDING,
        SCHEDULE
    }

    private static final int MAX_METADATA_LENGTH = 180;
    private static final String[] PAYMENT_KEYWORDS = {
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成",
        "支付失败", "付款失败", "已支付", "已付款", "支付", "付款",
        "收款", "微信", "支付宝", "人民币", "RMB", "¥", "￥", "元"
    };

    private ProbePrivacyPolicy() {
    }

    static boolean isPaymentPackage(String packageName) {
        return "com.eg.android.AlipayGphone".equals(packageName)
                || "com.tencent.watchwechat".equals(packageName)
                || "com.heytap.wearable.wallet".equals(packageName);
    }

    static boolean shouldPersistSnapshot(String rootPackage) {
        return isPaymentPackage(rootPackage);
    }

    static ScheduleAction decideSnapshotSchedule(
            boolean paymentTrigger,
            boolean hasPending,
            boolean pendingPayment,
            long elapsedSinceLast,
            long minimumInterval) {
        if (hasPending) {
            return paymentTrigger && !pendingPayment
                    ? ScheduleAction.REPLACE_PENDING
                    : ScheduleAction.KEEP_PENDING;
        }
        if (!paymentTrigger && elapsedSinceLast < minimumInterval) {
            return ScheduleAction.DROP;
        }
        return ScheduleAction.SCHEDULE;
    }

    static long snapshotDelay(
            boolean paymentTrigger,
            long elapsedSinceLast,
            long minimumInterval,
            long settleDelay) {
        if (!paymentTrigger || elapsedSinceLast >= minimumInterval) {
            return settleDelay;
        }
        return Math.max(settleDelay, minimumInterval - elapsedSinceLast);
    }

    static String summarizeTextList(List<CharSequence> values) {
        if (values == null || values.isEmpty()) {
            return "{count=0}";
        }
        StringBuilder result = new StringBuilder("{count=")
                .append(values.size())
                .append(",items=[");
        int limit = Math.min(values.size(), 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(summarizeText(values.get(i)));
        }
        if (values.size() > limit) {
            result.append(",…");
        }
        return result.append("]}").toString();
    }

    static String summarizeText(Object value) {
        if (value == null) {
            return "null";
        }
        String normalized = String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        if (normalized.isEmpty()) {
            return "{present=false}";
        }

        int digitCount = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isDigit(normalized.charAt(i))) {
                digitCount++;
            }
        }
        boolean hasCurrency = normalized.contains("¥")
                || normalized.contains("￥")
                || normalized.contains("人民币")
                || normalized.toUpperCase(Locale.ROOT).contains("RMB");
        boolean decimalAmountLike = normalized.matches(".*\\d+[.,]\\d{1,2}.*");

        StringBuilder keywords = new StringBuilder("[");
        boolean first = true;
        for (String keyword : PAYMENT_KEYWORDS) {
            if (!normalized.contains(keyword)) {
                continue;
            }
            if (!first) {
                keywords.append('|');
            }
            keywords.append(keyword);
            first = false;
        }
        keywords.append(']');

        return "{present=true"
                + ",length=" + normalized.length()
                + ",digitCount=" + digitCount
                + ",hasCurrency=" + hasCurrency
                + ",decimalAmountLike=" + decimalAmountLike
                + ",paymentKeywords=" + keywords
                + "}";
    }

    static String cleanMetadata(Object value) {
        if (value == null) {
            return "null";
        }
        String cleaned = String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ');
        if (cleaned.length() > MAX_METADATA_LENGTH) {
            return cleaned.substring(0, MAX_METADATA_LENGTH) + "…";
        }
        return cleaned;
    }
}
