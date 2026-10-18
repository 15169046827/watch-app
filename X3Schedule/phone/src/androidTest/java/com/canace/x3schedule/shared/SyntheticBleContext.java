package com.canace.x3schedule.shared;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** Fails transport tests if an indirect callback attempts to open a non-synthetic database. */
final class SyntheticBleContext extends ContextWrapper {
    private volatile boolean forbiddenAccess;

    SyntheticBleContext(Context context) {
        super(context);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public File getDatabasePath(String name) {
        check(name);
        return super.getDatabasePath(name);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        check(name);
        return super.openOrCreateDatabase(name, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        check(name);
        return super.openOrCreateDatabase(name, mode, factory, errorHandler);
    }

    private void check(String name) {
        if (!name.matches("schedule-test-ble-[a-f0-9-]+\\.db")) {
            forbiddenAccess = true;
            throw new IllegalStateException("Synthetic BLE attempted a non-test database");
        }
    }

    boolean isolated() {
        return !forbiddenAccess;
    }
}
