package com.canace.x3voiceledger.phone;

import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Runs the phone side of the test-only BLE replay, idempotency, and ACK acceptance. */
final class SyntheticBleCentralAcceptance {
    private static final UUID SERVICE_UUID =
            UUID.fromString("7bc0f101-9d38-4f90-83a2-4f77f3f0a100");
    private static final UUID DATA_UUID =
            UUID.fromString("7bc0f102-9d38-4f90-83a2-4f77f3f0a100");
    private static final UUID ACK_UUID =
            UUID.fromString("7bc0f103-9d38-4f90-83a2-4f77f3f0a100");
    private static final UUID CONFIG_UUID =
            UUID.fromString("7bc0f104-9d38-4f90-83a2-4f77f3f0a100");
    private static final String SYNTHETIC_ID = "synthetic-ble-acceptance-v1";
    private static final long PHASE_TIMEOUT_SECONDS = 75L;

    private final Context context;

    SyntheticBleCentralAcceptance(Context context) {
        this.context = context;
    }

    void run() throws Exception {
        IsolatedContext isolated = new IsolatedContext(context);
        require(
                !isolated.getDatabasePath("phone_ledger.db")
                        .equals(context.getDatabasePath("phone_ledger.db")),
                "Synthetic BLE database is not isolated");
        PhoneLedgerStore store = null;
        try {
            store = new PhoneLedgerStore(isolated);
            runPhase(store, true);
            require(store.count() == 1, "First BLE receive did not persist exactly one row");
            Thread.sleep(500L);
            runPhase(store, false);
            require(store.count() == 1, "Replay created a duplicate row");
            verifyStoredEntry(store);
        } finally {
            if (store != null) {
                store.close();
            }
            isolated.cleanup();
        }
    }

    private void runPhase(PhoneLedgerStore store, boolean expectAckFailure) throws Exception {
        PhaseCallback callback = new PhaseCallback(store, expectAckFailure);
        BleCentralClient client =
                new BleCentralClient(
                        context,
                        callback,
                        null,
                        SERVICE_UUID,
                        DATA_UUID,
                        ACK_UUID,
                        CONFIG_UUID,
                        ScanSettings.SCAN_MODE_LOW_LATENCY);
        callback.attach(client);
        client.start();
        require(
                callback.await(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                expectAckFailure ? "Initial BLE receive timed out" : "BLE retry timed out");
        client.stop();
        callback.throwFailure();
    }

    private static void verifyStoredEntry(PhoneLedgerStore store) {
        PhoneLedgerStore.Entry entry = store.find(SYNTHETIC_ID);
        require(entry != null, "Synthetic ledger is missing after BLE acceptance");
        require(entry.amountCents == 1234L, "Synthetic amount changed in transit");
        require(
                "synthetic BLE acceptance".equals(entry.note),
                "Synthetic note changed in transit");
        require(
                "synthetic_ble_acceptance".equals(entry.source),
                "Synthetic source changed in transit");
    }

    private static void validatePayload(JSONObject payload) {
        require("ledger".equals(payload.optString("type")), "Unexpected BLE payload type");
        require(payload.optInt("schema") == 2, "Unexpected BLE schema");
        require(SYNTHETIC_ID.equals(payload.optString("id")), "Unexpected synthetic ledger id");
        require(payload.optLong("amountCents") == 1234L, "Unexpected synthetic amount");
        require(
                "synthetic_ble_acceptance".equals(payload.optString("source")),
                "Unexpected synthetic source");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class PhaseCallback implements BleCentralClient.Callback {
        private final PhoneLedgerStore store;
        private final boolean expectAckFailure;
        private final CountDownLatch complete = new CountDownLatch(1);
        private volatile Throwable failure;
        private int ledgerCount;
        private BleCentralClient client;

        PhaseCallback(PhoneLedgerStore store, boolean expectAckFailure) {
            this.store = store;
            this.expectAckFailure = expectAckFailure;
        }

        void attach(BleCentralClient client) {
            this.client = client;
        }

        @Override
        public void onState(String state) {
        }

        @Override
        public void onLedger(JSONObject payload) {
            try {
                validatePayload(payload);
                require(ledgerCount == 0, "BLE phase received the same ledger more than once");
                require(store.upsert(payload), "Synthetic ledger revision was rejected");
                require(store.count() == 1, "BLE replay created a duplicate row");
                ledgerCount++;
            } catch (Throwable error) {
                fail(error);
            }
        }

        @Override
        public void onFinished() {
            if (expectAckFailure) {
                fail(new AssertionError("Initial ACK unexpectedly succeeded"));
                return;
            }
            if (ledgerCount != 1) {
                fail(new AssertionError("Retry did not receive exactly one replay"));
                return;
            }
            complete.countDown();
        }

        @Override
        public void onError(String message) {
            if (expectAckFailure
                    && ledgerCount == 1
                    && (message.startsWith("确认账单失败")
                            || message.startsWith("手表连接已断开"))) {
                complete.countDown();
                return;
            }
            fail(new AssertionError(message));
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return complete.await(timeout, unit);
        }

        void throwFailure() throws Exception {
            Throwable error = failure;
            if (error == null) {
                return;
            }
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            throw new AssertionError(error);
        }

        private void fail(Throwable error) {
            failure = error;
            if (client != null) {
                client.stop();
            }
            complete.countDown();
        }
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final String prefix =
                "synthetic_ble_" + UUID.randomUUID().toString().replace("-", "") + "_";

        IsolatedContext(Context base) {
            super(base);
        }

        @Override
        public File getDatabasePath(String name) {
            return super.getDatabasePath(prefix + name);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return super.openOrCreateDatabase(prefix + name, mode, factory);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name,
                int mode,
                SQLiteDatabase.CursorFactory factory,
                DatabaseErrorHandler errorHandler) {
            return super.openOrCreateDatabase(prefix + name, mode, factory, errorHandler);
        }

        void cleanup() {
            getBaseContext().deleteDatabase(prefix + "phone_ledger.db");
        }
    }
}
