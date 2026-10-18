package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

/** Read-only public API evidence; does not access databases or register/cancel alarms. */
final class AlarmCapabilityChecks {
    private AlarmCapabilityChecks() {
    }

    static void run(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        boolean exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms();
        AlarmManager.AlarmClockInfo next = manager.getNextAlarmClock();
        PendingIntent show = next == null ? null : next.getShowIntent();
        boolean owned = show != null && context.getPackageName().equals(show.getCreatorPackage());
        String immutable = owned && Build.VERSION.SDK_INT >= 31 ? Boolean.toString(show.isImmutable()) : "unknown";
        Bundle result = new Bundle();
        result.putString("stream", "Public alarm state: exact=" + exact + "; nextOwned=" + owned
                + "; nextAt=" + (owned ? next.getTriggerTime() : 0) + "; showImmutable="
                + immutable);
        instrumentation.finish(Activity.RESULT_OK, result);
    }
}
