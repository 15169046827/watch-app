package com.canace.x3schedule.shared;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Validation-only foreground control; never opens a schedule database. */
public final class RecoveryControlActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label = new TextView(this);
        label.setText("连接恢复对照\n请保持此页打开");
        label.setTextSize(18);
        label.setGravity(Gravity.CENTER);
        setContentView(label);
    }
}
