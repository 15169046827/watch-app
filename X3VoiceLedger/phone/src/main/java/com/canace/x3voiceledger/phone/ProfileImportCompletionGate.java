package com.canace.x3voiceledger.phone;

import java.util.concurrent.atomic.AtomicBoolean;

final class ProfileImportCompletionGate {
    private final AtomicBoolean open = new AtomicBoolean(true);

    boolean isOpen() {
        return open.get();
    }

    void close() {
        open.set(false);
    }
}