package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.canace.x3schedule.domain.Backup;
import com.canace.x3schedule.domain.Wire;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;

/** Real Activity result dispatch and writer, restricted to the persistent synthetic background app. */
final class DocumentResultChecks {
    void run(Instrumentation runner) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        ScheduleActivity activity = null;
        File file = null;
        try {
            Context context = runner.getTargetContext();
            require(context.getPackageName().equals("com.canace.x3schedule.phone.background.phone"),
                    "Document result checks require the background phone fixture");
            require(!context.getSystemService(KeyguardManager.class).isKeyguardLocked(), "Unlock the phone first");
            Wire.Snapshot before;
            try (Store store = new Store(context)) {
                require(store.meta("background_fixture", "").equals("1"), "Missing synthetic fixture");
                before = store.snapshot(true);
            }
            file = new File(context.getCacheDir(), "document-result-" + UUID.randomUUID() + ".x3backup");
            activity = open(runner);
            ScheduleActivity target = activity;
            Intent data = new Intent().setData(Uri.fromFile(file));
            // 24 is the request sent by the real backup export button, not the companion request.
            runner.runOnMainSync(() -> target.onActivityResult(24, Activity.RESULT_CANCELED, data));
            require(!file.exists(), "Canceled export created a file");
            runner.runOnMainSync(() -> target.onActivityResult(24, Activity.RESULT_OK, data));
            Wire.Snapshot exported = await(file);
            require(Wire.JSON.toJson(before.plans).equals(Wire.JSON.toJson(exported.plans)), "Plan export mismatch");
            require(Wire.JSON.toJson(before.events).equals(Wire.JSON.toJson(exported.events)), "Event export mismatch");
            result.putString("stream", "Document result checks passed; plans=" + exported.plans.size()
                    + "; events=" + exported.events.size() + "; bytes=" + file.length());
        } catch (Exception error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Document result checks failed; " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        } finally {
            if (activity != null) {
                ScheduleActivity target = activity;
                runner.runOnMainSync(target::finish);
            }
            if (file != null && file.exists() && !file.delete()) {
                code = Activity.RESULT_CANCELED;
                result.putString("stream", "Document test cleanup failed");
            }
        }
        runner.finish(code, result);
    }

    private ScheduleActivity open(Instrumentation runner) throws IOException {
        String name = ScheduleActivity.class.getName();
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(name, null, false);
        try (ParcelFileDescriptor output = runner.getUiAutomation().executeShellCommand("am start -n "
                + runner.getTargetContext().getPackageName() + "/" + name)) {
            Activity opened = runner.waitForMonitorWithTimeout(monitor, 15000);
            require(opened instanceof ScheduleActivity, "Activity did not open");
            runner.waitForIdleSync();
            return (ScheduleActivity) opened;
        } finally {
            runner.removeMonitor(monitor);
        }
    }

    private Wire.Snapshot await(File file) throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile() && file.length() > 0) {
                try (FileInputStream input = new FileInputStream(file)) {
                    return Backup.read(input);
                } catch (IOException | IllegalArgumentException incomplete) {
                    // The real writer runs asynchronously; retry only inside this bounded test window.
                }
            }
            Thread.sleep(200);
        }
        throw new IOException("Backup export callback did not produce a valid archive");
    }
}
