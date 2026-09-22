package com.canace.x3offlinereader.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Two-page, touch-friendly horizontal pager without an additional UI dependency. */
public final class HorizontalPager extends HorizontalScrollView {

    public interface Listener {

        void onPageChanged(int page);
    }

    private float downX;

    private int startPage;

    private int page;

    private Listener listener;

    public HorizontalPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        notifyPage();
    }

    public int currentPage() {
        return page;
    }

    public void setCurrentPage(int value, boolean animate) {
        int target = Math.max(0, Math.min(1, value));
        page = target;
        if (animate) {
            smoothScrollTo(target * getWidth(), 0);
        } else {
            scrollTo(target * getWidth(), 0);
        }
        notifyPage();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        View child = getChildAt(0);
        if (child instanceof LinearLayout) {
            LinearLayout pages = (LinearLayout) child;
            for (int i = 0; i < pages.getChildCount(); i++) {
                View view = pages.getChildAt(i);
                LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams) view.getLayoutParams();
                params.width = w;
                view.setLayoutParams(params);
            }
        }
        post(() -> scrollTo(page * w, 0));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            startPage = page;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            startPage = page;
        }
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            float distance = downX - event.getX();
            if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(distance) < 10) {
                performClick();
            }
            float threshold = Math.max(32f, getWidth() / 10f);
            int target =
                    Math.abs(distance) >= threshold
                            ? startPage + (distance > 0 ? 1 : -1)
                            : Math.round(getScrollX() / (float) Math.max(1, getWidth()));
            setCurrentPage(target, true);
        }
        return handled;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void fling(int velocityX) {
        setCurrentPage(page + (velocityX > 0 ? 1 : -1), true);
    }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (getWidth() > 0) {
            int next = x >= getWidth() / 2 ? 1 : 0;
            if (next != page) {
                page = next;
                notifyPage();
            }
        }
    }

    private void notifyPage() {
        if (listener != null) {
            listener.onPageChanged(page);
        }
    }
}
