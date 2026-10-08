package com.canace.x3schedule.shared;

import android.app.AlarmManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/** Opt-in troubleshooting build: timestamps and numeric states only, never schedule content or IDs. */
final class ReminderTrace {
    private ReminderTrace() {
    }

    static void event(String stage, boolean value) {
        event(stage, value ? 1 : 0);
    }

    static void event(String stage, long value) {
        if (BuildConfig.REMINDER_DIAGNOSTICS) {
            Log.i("ScheduleReminder", stage + " wall=" + System.currentTimeMillis()
                    + " elapsed=" + SystemClock.elapsedRealtime() + " value=" + value);
        }
    }

    static void alarmState(Context context, AlarmManager manager, boolean exact) {
        if (!BuildConfig.REMINDER_DIAGNOSTICS) {
            return;
        }
        try {
            event("EXACT_CAPABILITY", exact);
            PowerManager power = context.getSystemService(PowerManager.class);
            event("POWER_OPT_OUT", power.isIgnoringBatteryOptimizations(context.getPackageName()));
            AlarmManager.AlarmClockInfo clock = manager.getNextAlarmClock();
            event("CLOCK_REGISTERED", clock != null && clock.getShowIntent() != null && context.getPackageName()
                    .equals(clock.getShowIntent().getCreatorPackage()));
        } catch (RuntimeException error) {
            event("DIAGNOSTIC_UNAVAILABLE", 0);
        }
    }
}
