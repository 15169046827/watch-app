package com.canace.x3voiceledger.phone;

public final class LedgerBackupManagerSelfTest {
    private LedgerBackupManagerSelfTest() { }

    public static void main(String[] args) {
        check(BackupPolicy.supports("x3voiceledger-backup", 1), "schema 1 must be accepted");
        check(!BackupPolicy.supports("x3voiceledger-backup", 2), "future schemas must fail");
        check(BackupPolicy.isValidEntryIdentity("entry-1", 10L), "valid identity rejected");
        check(!BackupPolicy.isValidEntryIdentity("", 10L), "blank ids must fail");
        check(!BackupPolicy.isValidEntryIdentity("entry-1", 0L), "zero revisions must fail");
        check(BackupPolicy.isValidText(null), "missing optional text must be valid");
        check(!BackupPolicy.isValidText("x".repeat(8193)), "oversized text must fail");
        System.out.println("Ledger backup policy self-tests passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}