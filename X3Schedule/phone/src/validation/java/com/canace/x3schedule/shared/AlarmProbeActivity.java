package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

/** Validation UID only: two independent alarms, no Store, sync, jobs or automatic rescheduling. */
public final class AlarmProbeActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!AlarmProbeReceiver.allowed(this)) {
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = ScheduleUi.dp(this, 28);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);
        ScheduleUi.heading(content, "独立闹钟对照");
        ScheduleUi.text(content, "测试专用，不读取日程。两种独立唤醒闹钟在三分钟后触发；准备后正常息屏。", 14);
        ScheduleUi.button(content, "准备三分钟对照", this::prepare);
        ScheduleUi.button(content, "取消对照", this::cancel);
    }

    private PendingIntent operation(int type, long target) {
        Intent intent = new Intent(this, AlarmProbeReceiver.class)
                .setAction("com.canace.x3schedule.PROBE_" + type)
                .putExtra("type", type).putExtra("target", target);
        return PendingIntent.getBroadcast(this, 710 + type, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void prepare() {
        AlarmManager manager = getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            Toast.makeText(this, "缺少精确闹钟权限，未开始对照", Toast.LENGTH_LONG).show();
            return;
        }
        cancel();
        long target = System.currentTimeMillis() + 180000;
        long elapsedTarget = SystemClock.elapsedRealtime() + 180000;
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, operation(0, target));
        manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                elapsedTarget, operation(2, target));
        Log.i("ScheduleAlarmProbe", "REQUEST type=0 target=" + target + " trigger=" + target);
        Log.i("ScheduleAlarmProbe", "REQUEST type=2 target=" + target + " trigger=" + elapsedTarget);
        Toast.makeText(this, "已准备，三分钟后对照", Toast.LENGTH_LONG).show();
    }

    private void cancel() {
        AlarmManager manager = getSystemService(AlarmManager.class);
        manager.cancel(operation(0, 0));
        manager.cancel(operation(2, 0));
    }
}
