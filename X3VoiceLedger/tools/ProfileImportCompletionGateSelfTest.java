package com.canace.x3voiceledger.phone;

public final class ProfileImportCompletionGateSelfTest {
    public static void main(String[] args) {
        ProfileImportCompletionGate gate = new ProfileImportCompletionGate();
        expect(gate.isOpen(), "new gate should accept results");
        gate.close();
        expect(!gate.isOpen(), "closed gate should reject results");
        gate.close();
        expect(!gate.isOpen(), "close should be idempotent");
        System.out.println("ProfileImportCompletionGateSelfTest: all cases passed");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}