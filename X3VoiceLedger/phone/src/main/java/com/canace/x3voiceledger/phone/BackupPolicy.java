package com.canace.x3voiceledger.phone;

public final class BackupPolicy {
    private static final String TYPE = "x3voiceledger-backup";
    private static final int SCHEMA = 1;
    private static final int MAX_ID_LENGTH = 200;
    private static final int MAX_TEXT_LENGTH = 8192;

    private BackupPolicy() { }

    public static boolean supports(String type, int schema) {
        return TYPE.equals(type) && schema == SCHEMA;
    }

    public static boolean isValidEntryIdentity(String id, long updatedAt) {
        String value = id == null ? "" : id.trim();
        return !value.isEmpty() && value.length() <= MAX_ID_LENGTH && updatedAt > 0L;
    }

    public static boolean isValidText(String value) {
        return value == null || value.length() <= MAX_TEXT_LENGTH;
    }
}