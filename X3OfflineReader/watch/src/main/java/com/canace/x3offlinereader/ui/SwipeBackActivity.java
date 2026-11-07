package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.MotionEvent;
import com.canace.x3offlinereader.MainActivity;

/** Consistent right-swipe exit without depending on Wear OS navigation. */
public abstract class SwipeBackActivity extends Activity {

    private float downX;

    private float downY;

    private boolean enabled;

    private boolean swipeBackEligible;

    protected final void enableSwipeBack(int rootId) {
        enabled = findViewById(rootId) != null;
    }

    protected boolean canSwipeBack() {
        return true;
    }

    protected Intent rootBackParent() {
        return null;
    }

    @Override
    public void onBackPressed() {
        if (isTaskRoot()) {
            Intent parent = rootBackParent();
            if (parent != null) {
                startActivities(new Intent[] {MainActivity.homeIntent(this), parent});
                finish();
                return;
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        swipeBackEligible = false;
        super.onPause();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (enabled && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            swipeBackEligible = canSwipeBack();
        }
        if (enabled
                && swipeBackEligible
                && event.getActionMasked() == MotionEvent.ACTION_UP
                && event.getX() - downX >= 96f
                && Math.abs(event.getY() - downY) < 120f) {
            swipeBackEligible = false;
            onBackPressed();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            swipeBackEligible = false;
        }
        return super.dispatchTouchEvent(event);
    }
}
