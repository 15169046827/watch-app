package com.canace.x3voiceledger.phone;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Synthetic backup acceptance using namespaced test storage only. */
public final class SyntheticBackupRunner extends Instrumentation {
    private String scenario;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        scenario = arguments == null ? "backup" : arguments.getString("scenario", "backup");
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("ble".equals(scenario)) {
                verifyBle();
                result.putString("stream", "PHONE_SYNTHETIC_BLE_OK\n");
            } else if ("backup".equals(scenario)) {
                verifyRoundTrip();
                result.putString("stream", "PHONE_SYNTHETIC_BACKUP_OK\n");
            } else {
                throw new IllegalArgumentException("Unknown scenario: " + scenario);
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            String failureMarker =
                    "ble".equals(scenario)
                            ? "PHONE_SYNTHETIC_BLE_FAILED"
                            : "PHONE_SYNTHETIC_BACKUP_FAILED";
            result.putString(
                    "stream", failureMarker + ": "
                            + error.getClass().getSimpleName() + ": "
                            + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifyBle() throws Exception {
        Intent foregroundIntent =
                new Intent(getTargetContext(), SyntheticBleForegroundActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(foregroundIntent);
        Thread.sleep(5_000L);
        try {
            new SyntheticBleCentralAcceptance(getTargetContext()).run();
        } finally {
            Intent finishIntent =
                    new Intent(getTargetContext(), SyntheticBleForegroundActivity.class)
                            .setAction(SyntheticBleForegroundActivity.ACTION_FINISH)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getTargetContext().startActivity(finishIntent);
        }
    }

    private void verifyRoundTrip() throws Exception {
        Context real = getTargetContext();
        IsolatedContext sourceContext = new IsolatedContext(real);
        IsolatedContext targetContext = new IsolatedContext(real);
        require(!sourceContext.getDatabasePath("phone_ledger.db")
                        .equals(real.getDatabasePath("phone_ledger.db")),
                "Database isolation failed");
        PhoneLedgerStore source = null;
        PhoneLedgerStore target = null;
        try {
            source = new PhoneLedgerStore(sourceContext);
            target = new PhoneLedgerStore(targetContext);
            LedgerProfileStore sourceProfile = new LedgerProfileStore(sourceContext);
            CategoryRuleStore sourceRules = new CategoryRuleStore(sourceContext);
            LedgerProfileStore targetProfile = new LedgerProfileStore(targetContext);
            CategoryRuleStore targetRules = new CategoryRuleStore(targetContext);

            source.upsert(entry("same-id", 10L, "old"));
            source.upsert(entry("new-id", 11L, "new"));
            target.upsert(entry("same-id", 20L, "current"));
            byte[] bytes = LedgerBackupManager.create(source, sourceProfile, sourceRules);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            LedgerBackupManager.validate(root);
            LedgerBackupManager.ParsedBackup backup = new LedgerBackupManager.ParsedBackup(root);
            require(backup.entryCount == 2, "Export count mismatch");

            require(LedgerBackupManager.restore(backup, target, targetProfile, targetRules) == 1,
                    "First restore must insert only the missing entry");
            require(target.count() == 2, "Target count mismatch");
            require(LedgerBackupManager.restore(backup, target, targetProfile, targetRules) == 0,
                    "Repeated restore must be idempotent");
            require(target.count() == 2, "Repeated restore changed target count");
            require(containsRevision(target, "same-id", 20L),
                    "Older backup overwrote newer local revision");

            root.put("schema", 2);
            try {
                LedgerBackupManager.validate(root);
                throw new AssertionError("Future schema was accepted");
            } catch (JSONException expected) {
                require(target.count() == 2, "Rejected backup changed target data");
            }
        } finally {
            if (source != null) {
                source.close();
            }
            if (target != null) {
                target.close();
            }
            sourceContext.cleanup();
            targetContext.cleanup();
        }
    }

    private static JSONObject entry(String id, long revision, String note) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("createdAt", revision)
                .put("updatedAt", revision)
                .put("transactionTime", revision)
                .put("amountCents", 100)
                .put("note", note)
                .put("category", "synthetic")
                .put("locationLabel", "");
    }

    private static boolean containsRevision(PhoneLedgerStore store, String id, long revision)
            throws JSONException {
        JSONArray entries = store.exportEntries();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            if (id.equals(entry.optString("id"))) {
                return entry.optLong("updatedAt") == revision;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final String prefix = "synthetic_backup_"
                + UUID.randomUUID().toString().replace("-", "") + "_";

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
                String name, int mode, SQLiteDatabase.CursorFactory factory,
                DatabaseErrorHandler errorHandler) {
            return super.openOrCreateDatabase(prefix + name, mode, factory, errorHandler);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences(prefix + name, mode);
        }

        void cleanup() {
            getBaseContext().deleteDatabase(prefix + "phone_ledger.db");
            getBaseContext().deleteSharedPreferences(prefix + "ledger_profile");
            getBaseContext().deleteSharedPreferences(prefix + "category_rules");
        }
    }
}
