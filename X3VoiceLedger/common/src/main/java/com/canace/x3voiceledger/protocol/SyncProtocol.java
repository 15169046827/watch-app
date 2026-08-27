package com.canace.x3voiceledger.protocol;

import java.util.UUID;

public final class SyncProtocol {
    public static final int SCHEMA_VERSION = 2;
    public static final UUID SERVICE_UUID = UUID.fromString("7bc02101-9d38-4f90-83a2-4f77f3f0a100");
    public static final UUID DATA_UUID = UUID.fromString("7bc02102-9d38-4f90-83a2-4f77f3f0a100");
    public static final UUID ACK_UUID = UUID.fromString("7bc02103-9d38-4f90-83a2-4f77f3f0a100");
    public static final UUID CONFIG_UUID = UUID.fromString("7bc02104-9d38-4f90-83a2-4f77f3f0a100");
    public static final String TYPE_LEDGER = "ledger";
    public static final String TYPE_EMPTY = "empty";

    private SyncProtocol() { }
}
