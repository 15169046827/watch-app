package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Instrumentation;
import android.content.Context;
import android.database.DatabaseUtils;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Host-coordinated link loss and screen-off recovery; instrumentation is not a production process-lifetime test. */
final class BleRecoveryChecks {
    private final Instrumentation runner;
    private final Context context;
    private final File directory;
    private final String title;
    private final boolean foreground;

    BleRecoveryChecks(Instrumentation runner, Context context, String id, boolean foreground) {
        require(id.matches("[a-f0-9]{32}"), "Invalid recovery ID");
        this.runner = runner;
        this.foreground = foreground;
        this.context = context;
        directory = new File(context.getFilesDir(), "ble-recovery-" + id);
        title = "离线补传-" + id;
    }

    void run(Store store, BleLink original) throws IOException, InterruptedException {
        require(directory.mkdir(), "Recovery ID must be fresh");
        Map<String, String> history = new HashMap<>();
        for (Event event : store.events()) {
            history.put(event.id, Wire.JSON.toJson(event));
        }
        phase("ready");
        if (store.phone) {
            command("edit", 120000);
            Plan added = new Plan();
            added.title = title;
            added.kind = Kind.REMINDER;
            added.rule = Rule.DAYS;
            added.anchor = LocalDate.now().plusDays(3).toString();
            added.count = 1;
            added.reminderTargets = 0;
            store.save(added);
            require(store.pending() > 0 && store.events().size() == 1, "Phone offline mutation not isolated");
            phase("offline");
            command("recover", 120000);
            await(store, original, history);
        } else {
            command("disconnect", 120000);
            original.close();
            Thread.sleep(1000);
            Instance execution = store.instances().stream().filter(item -> item.kind == Kind.EXECUTION)
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing execution fixture"));
            store.record(execution, Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
            require(store.pending() > 0 && store.plans().size() == 3, "Watch offline mutation not isolated");
            phase("offline");
            command("recover", 120000);
            try (BleLink replacement = new BleLink(context, store, () -> { }, () -> { })) {
                replacement.start();
                await(store, replacement, history);
            } finally {
                Thread.sleep(1000);
            }
        }
    }

    private void await(Store store, BleLink link, Map<String, String> history)
            throws InterruptedException, IOException {
        long started = SystemClock.elapsedRealtime();
        long uptime = SystemClock.uptimeMillis();
        PowerManager power = context.getSystemService(PowerManager.class);
        int samples = 0;
        long nextReport = started;
        while (SystemClock.elapsedRealtime() - started < 240000) {
            require(power.isInteractive() == foreground, "Screen state changed during recovery");
            samples++;
            if (SystemClock.elapsedRealtime() >= nextReport) {
                phase("progress;status=" + link.status + ";plans=" + store.plans().size()
                        + ";events=" + store.events().size() + ";pending=" + store.pending()
                        + ";elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                        + ";awakeMs=" + (SystemClock.uptimeMillis() - uptime)
                        + ";staged=" + DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "batch_in_parts"));
                nextReport = SystemClock.elapsedRealtime() + 20000;
            }
            boolean complete = store.plans().size() == 4 && store.events().size() == 2 && store.pending() == 0;
            if (complete) {
                require(store.plans().stream().filter(plan -> title.equals(plan.title)).count() == 1,
                        "Offline plan missing or duplicated");
                for (Event event : store.events()) {
                    if (history.containsKey(event.id)) {
                        require(history.remove(event.id).equals(Wire.JSON.toJson(event)), "History changed");
                    }
                }
                require(history.isEmpty(), "History lost");
                phase("recovered;foreground=" + foreground + ";screenStateSamples=" + samples + ";elapsedMs="
                        + (SystemClock.elapsedRealtime() - started));
                command("finish", 120000);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Recovery timeout; status=" + link.status);
    }

    private void command(String expected, long timeout) throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        File file = new File(directory, "command");
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                require(bytes.length <= 128, "Oversized recovery command");
                if (expected.equals(new String(bytes, StandardCharsets.UTF_8).trim())) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Host command timeout: " + expected);
    }

    private void phase(String value) throws IOException {
        Files.write(new File(directory, "phase").toPath(), value.getBytes(StandardCharsets.UTF_8));
        Bundle status = new Bundle();
        status.putString("stream", "BLE recovery " + (context.getPackageName().endsWith(".phone") ? "phone " : "watch ")
                + value + "\n");
        runner.sendStatus(0, status);
    }
}
