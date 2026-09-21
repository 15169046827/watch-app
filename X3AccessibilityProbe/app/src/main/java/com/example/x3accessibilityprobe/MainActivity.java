package com.example.x3accessibilityprobe;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public final class MainActivity extends Activity {

    private static final int LOCATION_REQUEST = 7;
    private static final String PHYSICAL_KEY_TAG = "X3PhysicalKeyProbe";
    private static final String ROTARY_TAG = "X3RotaryProbe";
    private TextView statusView;
    private TextView actionResultView;
    private TextView bleResultView;
    private float gestureDownX;
    private float gestureDownY;
    private boolean preferenceListenerRegistered;

    private final SharedPreferences.OnSharedPreferenceChangeListener bleResultListener =
            (sharedPreferences, key) -> {
                if (BleProbeService.KEY_RESULTS.equals(key)) {
                    refreshBleResults();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean isRoundScreen = getResources().getConfiguration().isScreenRound();
        int roundInset = isRoundScreen ? calculateRoundCornerInset() : 0;
        int horizontalSafePadding = isRoundScreen ? roundInset + dp(8) : dp(12);
        int verticalSafePadding = isRoundScreen ? roundInset : dp(12);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(horizontalSafePadding, 0, horizontalSafePadding, 0);
        content.setBackgroundColor(Color.BLACK);

        TextView title = createText(getString(R.string.probe_title), 19f);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = createText("", 13f);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(12);
        content.addView(statusView, statusParams);

        Button refreshButton = createButton(getString(R.string.refresh_status));
        refreshButton.setOnClickListener(view -> refreshStatus());
        content.addView(refreshButton, buttonLayoutParams());

        Button settingsButton = createButton(getString(R.string.open_accessibility_settings));
        settingsButton.setOnClickListener(view -> openAccessibilitySettings());
        content.addView(settingsButton, buttonLayoutParams());

        Button bleButton = createButton(getString(R.string.run_ble_probe));
        bleButton.setOnClickListener(view -> runBleProbeWithPermission());
        content.addView(bleButton, buttonLayoutParams());

        bleResultView = createText("", 11f);
        bleResultView.setTextIsSelectable(true);
        content.addView(bleResultView, statusParams);

        TextView exitHint = createText(getString(R.string.swipe_right_to_exit), 12f);
        exitHint.setGravity(Gravity.CENTER);
        content.addView(exitHint, statusParams);

        actionResultView = createText("", 12f);
        actionResultView.setGravity(Gravity.CENTER);
        content.addView(actionResultView, statusParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(true);
        scrollView.setPadding(0, verticalSafePadding, 0, verticalSafePadding);
        scrollView.setBackgroundColor(Color.BLACK);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scrollView);
        getSharedPreferences(BleProbeService.PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(bleResultListener);
        preferenceListenerRegistered = true;
        refreshStatus();
        refreshBleResults();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        long heldMillis = Math.max(0L, event.getEventTime() - event.getDownTime());
        String action = event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP";
        String evidence = "action=" + action
                + " keyCode=" + event.getKeyCode()
                + " keyName=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " scanCode=" + event.getScanCode()
                + " repeat=" + event.getRepeatCount()
                + " long=" + event.isLongPress()
                + " heldMs=" + heldMillis;
        Log.i(PHYSICAL_KEY_TAG, evidence);
        if (actionResultView != null) {
            actionResultView.setText(evidence);
        }

        // This is an observation-only probe. Preserve Android and OPPO default handling.
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        String evidence = "action=" + MotionEvent.actionToString(event.getAction())
                + " source=0x" + Integer.toHexString(source)
                + " rotary=" + ((source & InputDevice.SOURCE_ROTARY_ENCODER)
                == InputDevice.SOURCE_ROTARY_ENCODER)
                + " axisScroll=" + event.getAxisValue(MotionEvent.AXIS_SCROLL)
                + " axisVScroll=" + event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                + " axisHScroll=" + event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                + " deviceId=" + event.getDeviceId();
        Log.i(ROTARY_TAG, evidence);
        if (actionResultView != null) {
            actionResultView.setText(evidence);
        }

        // Observation only: retain Android's default focus and scrolling behavior.
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureDownX = event.getX();
                gestureDownY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                float deltaX = event.getX() - gestureDownX;
                float deltaY = event.getY() - gestureDownY;
                float minimumDistance = Math.max(
                        dp(40),
                        getResources().getDisplayMetrics().widthPixels * 0.20f);
                if (deltaX >= minimumDistance
                        && Math.abs(deltaY) <= deltaX * 0.60f) {
                    finish();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            refreshStatus();
        }
    }

    @Override
    protected void onDestroy() {
        if (preferenceListenerRegistered) {
            getSharedPreferences(BleProbeService.PREFS, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(bleResultListener);
            preferenceListenerRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_REQUEST) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBleProbe();
        } else {
            bleResultView.setText(R.string.ble_permission_denied);
        }
    }

    private void runBleProbeWithPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startBleProbe();
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
        }
    }

    private void startBleProbe() {
        startForegroundService(new Intent(this, BleProbeService.class));
    }

    private void refreshBleResults() {
        if (bleResultView == null) {
            return;
        }
        bleResultView.setText(getSharedPreferences(BleProbeService.PREFS, MODE_PRIVATE)
                .getString(BleProbeService.KEY_RESULTS, getString(R.string.ble_waiting)));
    }

    private void refreshStatus() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> installed =
                manager.getInstalledAccessibilityServiceList();
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        boolean settingsAvailable = settingsIntent.resolveActivity(getPackageManager()) != null;

        statusView.setText(getString(
                R.string.probe_status,
                yesNo(containsProbe(installed)),
                manager.isEnabled() ? getString(R.string.status_on) : getString(R.string.status_off),
                yesNo(containsProbe(enabled)),
                settingsAvailable
                        ? getString(R.string.status_available)
                        : getString(R.string.status_unavailable),
                getResources().getConfiguration().isScreenRound()
                        ? getString(R.string.layout_round_safe)
                        : getString(R.string.layout_rectangular)));
    }

    private boolean containsProbe(List<AccessibilityServiceInfo> services) {
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                    && ProbeAccessibilityService.class.getName().equals(
                            info.getResolveInfo().serviceInfo.name)) {
                return true;
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) == null) {
            actionResultView.setText(R.string.settings_action_unavailable);
            refreshStatus();
            return;
        }

        try {
            startActivity(intent);
            actionResultView.setText(R.string.settings_action_launched);
        } catch (ActivityNotFoundException | SecurityException exception) {
            actionResultView.setText(getString(
                    R.string.settings_action_failed,
                    exception.getClass().getSimpleName()));
        }
    }

    private String yesNo(boolean value) {
        return getString(value ? R.string.status_yes : R.string.status_no);
    }

    private TextView createText(String text, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sizeSp);
        return view;
    }

    private Button createButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams buttonLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int calculateRoundCornerInset() {
        int diameter = Math.min(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        return (int) Math.ceil(diameter * (1.0 - Math.sqrt(0.5)) / 2.0);
    }
}
