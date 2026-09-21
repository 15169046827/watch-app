package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.view.MotionEvent;

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
            finish();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
}
