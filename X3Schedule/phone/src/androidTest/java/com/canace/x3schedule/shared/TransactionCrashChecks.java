package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Arrays;

/** Host-assisted process termination around a real Store batch in a separate synthetic database. */
final class TransactionCrashChecks {
    private final Instrumentation runner;
    private final Context context;
    private final String stage;
    private final String id;

    TransactionCrashChecks(Instrumentation runner, String stage, String id) {
        this.runner = runner;
        context = runner.getTargetContext();
        this.stage = stage;
        this.id = id;
    }

    void run() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            String pkg = context.getPackageName();
            require(pkg.equals("com.canace.x3schedule.phone.validation.phone")
                    || pkg.equals("com.canace.x3schedule.validation"), "Validation package only");
            require(id != null && id.matches("[a-f0-9]{32}"), "A fresh crash test ID is required");
            require(Arrays.asList("before", "after", "recover-before", "recover-after").contains(stage),
                    "Unknown crash stage");
            String name = "schedule-test-crash-" + id + ".db";
            boolean recovering = stage.startsWith("recover-");
            require(context.getDatabasePath(name).exists() == recovering, "Wrong synthetic database state");
            // Phone role exercises the writer on both SQLite engines; schedule.db is never opened here.
            try (Store store = new Store(context, name, true)) {
                if (recovering) {
                    recover(store);
                    result.putString("stream", "Transaction crash recovery passed: " + stage);
                } else {
                    prepare(store);
                    throw new IllegalStateException("Host did not terminate the process at the armed boundary");
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Transaction crash check interrupted");
        } catch (IOException | RuntimeException error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Transaction crash check failed: " + error.getMessage());
        }
        runner.finish(code, result);
    }

    private void prepare(Store store) throws IOException, InterruptedException {
        Plan original = plan("进程恢复历史基线", 1);
        store.save(original);
        store.record(store.instances().get(0), Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
        write("before", snapshot(store));
        Plan edit = Wire.copy(store.plans().get(0), Plan.class);
        edit.title = "进程恢复批量修改";
        Plan added = plan("进程恢复批量新增", 3);
        if (stage.equals("before")) {
            SQLiteDatabase db = store.getWritableDatabase();
            // The outer transaction gives the host a deterministic uncommitted boundary without a production hook.
            db.beginTransaction();
            try {
                store.saveAll(Arrays.asList(edit, added));
                require(!snapshot(store).equals(read("before")), "Batch must mutate before arming rollback");
                write("after", snapshot(store));
                arm();
                // A host timeout intentionally rolls back rather than accidentally retaining an incomplete test.
            } finally {
                db.endTransaction();
            }
        } else {
            store.saveAll(Arrays.asList(edit, added));
            write("after", snapshot(store));
            arm();
        }
    }

    private void recover(Store store) throws IOException {
        String expected = stage.equals("recover-before") ? "before" : "after";
        require(store.getReadableDatabase().isDatabaseIntegrityOk(), "Recovered database integrity failed");
        require(snapshot(store).equals(read(expected)), "Plans, instances, events, settings or revision differ");
        require(store.plans().size() == (expected.equals("before") ? 1 : 2), "Partial batch recovered");
        require(store.events().size() == 1, "Historical event lost");
    }

    private void arm() throws IOException, InterruptedException {
        write("armed", stage);
        Bundle status = new Bundle();
        status.putString("stream", "Crash boundary armed: " + stage);
        runner.sendStatus(0, status);
        Thread.sleep(30000);
    }

    private Plan plan(String title, int count) {
        Plan plan = new Plan();
        plan.title = title;
        plan.anchor = LocalDate.now().plusDays(1).toString();
        plan.rule = Rule.DAYS;
        plan.count = count;
        plan.reminderTargets = 0;
        return plan;
    }

    private String snapshot(Store store) {
        Wire.Snapshot state = store.snapshot(true);
        state.id = "crash-oracle";
        return Wire.JSON.toJson(state);
    }

    private File file(String suffix) {
        return new File(context.getFilesDir(), "schedule-crash-" + id + "-" + suffix);
    }

    private void write(String suffix, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file(suffix))) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private String read(String suffix) throws IOException {
        return new String(Files.readAllBytes(file(suffix).toPath()), StandardCharsets.UTF_8);
    }
}
