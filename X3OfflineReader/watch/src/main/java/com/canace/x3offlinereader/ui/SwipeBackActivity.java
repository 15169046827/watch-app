package com.canace.x3offlinereader.ui;

import android.app.Activity;
import android.view.MotionEvent;

/** Consistent right-swipe exit without depending on Wear OS navigation. */
public abstract class SwipeBackActivity extends Activity {
    private float downX,downY;private boolean enabled;
    protected final void enableSwipeBack(int rootId){
        enabled=findViewById(rootId)!=null;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(enabled&&event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();}
        if(enabled&&event.getActionMasked()==MotionEvent.ACTION_UP&&event.getX()-downX>=96f&&Math.abs(event.getY()-downY)<120f){finish();return true;}
        return super.dispatchTouchEvent(event);
    }
}
