package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import java.io.IOException;

/** Scoped foreground window used only by the explicit recovery control mode. */
final class RecoveryControl implements AutoCloseable {
    private final Instrumentation runner;
    private final Activity activity;

    RecoveryControl(Instrumentation runner, boolean foreground) throws IOException {
        this.runner = runner;
        if (foreground) {
            require(runner.getTargetContext().getSystemService(PowerManager.class).isInteractive(),
                    "Unlock the device before foreground control");
            require(!runner.getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(),
                    "Unlock the device before foreground control");
            activity = open();
        } else {
            activity = null;
        }
    }

    private Activity open() throws IOException {
        String name = "com.canace.x3schedule.shared.RecoveryControlActivity";
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(name, null, false);
        try {
            // DUMP restricts this validation-only Activity to the shell/system, not arbitrary apps.
            String command = "am start -n " + runner.getTargetContext().getPackageName() + "/" + name;
            try (ParcelFileDescriptor output = runner.getUiAutomation().executeShellCommand(command)) {
                Activity opened = runner.waitForMonitorWithTimeout(monitor, 15000);
                require(opened != null, "Foreground control window did not open");
                runner.waitForIdleSync();
                return opened;
            }
        } finally {
            runner.removeMonitor(monitor);
        }
    }

    @Override
    public void close() {
        if (activity != null) {
            runner.runOnMainSync(activity::finish);
        }
    }

    Activity activity() {
        return activity;
    }
}
