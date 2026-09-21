package com.canace.x3voiceledger.phone;

public final class IncomingRevisionPolicy {
    private IncomingRevisionPolicy() { }

    public static boolean shouldApply(long incomingUpdatedAt, Long localUpdatedAt) {
        return localUpdatedAt == null || incomingUpdatedAt >= localUpdatedAt;
    }
}
