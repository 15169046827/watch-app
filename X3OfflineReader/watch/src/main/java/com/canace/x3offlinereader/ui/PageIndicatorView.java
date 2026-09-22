package com.canace.x3offlinereader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.canace.x3offlinereader.R;

/** Compact two-page indicator sized for the watch's round display. */
public final class PageIndicatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float activeRadius;
    private final float inactiveRadius;
    private final float centerOffset;
    private int currentPage;

    public PageIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        activeRadius = 3.5f * density;
        inactiveRadius = 3f * density;
        centerOffset = 6.5f * density;
    }

    public void setCurrentPage(int page) {
        int next = page <= 0 ? 0 : 1;
        if (currentPage == next) {
            return;
        }
        currentPage = next;
        invalidate();
    }

    public int currentPage() {
        return currentPage;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerY = getHeight() / 2f;
        drawDot(canvas, getWidth() / 2f - centerOffset, centerY, currentPage == 0);
        drawDot(canvas, getWidth() / 2f + centerOffset, centerY, currentPage == 1);
    }

    private void drawDot(Canvas canvas, float x, float y, boolean active) {
        paint.setColor(
                getResources()
                        .getColor(active ? R.color.text_primary : R.color.text_secondary, null));
        paint.setAlpha(active ? 255 : 190);
        canvas.drawCircle(x, y, active ? activeRadius : inactiveRadius, paint);
    }
}
