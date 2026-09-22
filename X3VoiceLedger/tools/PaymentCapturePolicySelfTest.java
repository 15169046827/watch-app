package com.canace.x3voiceledger;

public final class PaymentCapturePolicySelfTest {
    private PaymentCapturePolicySelfTest() { }

    public static void main(String[] args) {
        require(
                !PaymentCapturePolicy.shouldCapture(false, true, true, false),
                "Capture must remain disabled before separate in-app consent");
        require(
                PaymentCapturePolicy.shouldCapture(true, true, true, false),
                "An eligible uncaptured success window should be accepted after consent");
        require(
                !PaymentCapturePolicy.shouldCapture(true, false, true, false),
                "Non-Alipay events must be rejected");
        require(
                !PaymentCapturePolicy.shouldCapture(true, true, false, false),
                "Non-success windows must be rejected");
        require(
                !PaymentCapturePolicy.shouldCapture(true, true, true, true),
                "The same success window must not be captured twice");
        require(
                PaymentCapturePolicy.LIMITATIONS.contains("可能")
                        && PaymentCapturePolicy.LIMITATIONS.contains("手工"),
                "The user-facing limitation text must retain uncertainty and fallback guidance");
        System.out.println("PaymentCapturePolicySelfTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}