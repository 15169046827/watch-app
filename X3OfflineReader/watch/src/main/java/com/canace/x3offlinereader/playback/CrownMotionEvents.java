package com.canace.x3offlinereader.playback;

import android.view.InputDevice;
import android.view.MotionEvent;

/** Public-API adapter for standard rotary input and the verified OWW261 crown path. */
public final class CrownMotionEvents {
    private CrownMotionEvents() { }

    public static float delta(MotionEvent event) {
        InputDevice device = event.getDevice();
        return CrownInputContract.selectDelta(
                event.getAction() == MotionEvent.ACTION_SCROLL,
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER),
                event.isFromSource(InputDevice.SOURCE_MOUSE),
                device == null ? null : device.getName(),
                event.getAxisValue(MotionEvent.AXIS_SCROLL),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL));
    }
}
