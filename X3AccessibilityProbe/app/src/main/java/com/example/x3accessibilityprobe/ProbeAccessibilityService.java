package com.example.x3accessibilityprobe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ProbeAccessibilityService extends AccessibilityService {

    private static final String TAG = "X3AccessibilityProbe";
    private static final long SNAPSHOT_INTERVAL_MS = 750L;
    private static final int MAX_SNAPSHOT_NODES = 80;
    private static final int MAX_SNAPSHOT_DEPTH = 12;
    private static final long SNAPSHOT_SETTLE_DELAY_MS = 250L;
    private static final long MAX_OFFLINE_LOG_BYTES = 512L * 1024L;
    private static final String OFFLINE_LOG_NAME = "payment-probe-sanitized.log";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastSnapshotElapsedMs;
    private Runnable pendingSnapshot;
    private boolean pendingSnapshotPayment;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "lifecycle=CONNECTED, privacyMode=STRUCTURED_REDACTION");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        AccessibilityNodeInfo source = event.getSource();
        try {
            recordInfo(describeEvent(event, source), false);
        } finally {
            if (source != null) {
                source.recycle();
            }
        }

        scheduleSnapshot(event);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "lifecycle=INTERRUPTED");
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        pendingSnapshot = null;
        pendingSnapshotPayment = false;
        super.onDestroy();
    }

    private void scheduleSnapshot(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long elapsedSinceLast = now - lastSnapshotElapsedMs;
        String triggerPackage = ProbePrivacyPolicy.cleanMetadata(event.getPackageName());
        boolean paymentTrigger = ProbePrivacyPolicy.isPaymentPackage(triggerPackage);
        int triggerEventType = type;
        ProbePrivacyPolicy.ScheduleAction action = ProbePrivacyPolicy.decideSnapshotSchedule(
                paymentTrigger,
                pendingSnapshot != null,
                pendingSnapshotPayment,
                elapsedSinceLast,
                SNAPSHOT_INTERVAL_MS);
        if (action == ProbePrivacyPolicy.ScheduleAction.DROP
                || action == ProbePrivacyPolicy.ScheduleAction.KEEP_PENDING) {
            return;
        }
        if (action == ProbePrivacyPolicy.ScheduleAction.REPLACE_PENDING) {
            mainHandler.removeCallbacks(pendingSnapshot);
        }

        pendingSnapshotPayment = paymentTrigger;
        pendingSnapshot = () -> {
            pendingSnapshot = null;
            pendingSnapshotPayment = false;
            lastSnapshotElapsedMs = SystemClock.elapsedRealtime();
            snapshotActiveWindow(triggerPackage, triggerEventType);
        };
        long delay = ProbePrivacyPolicy.snapshotDelay(
                paymentTrigger,
                elapsedSinceLast,
                SNAPSHOT_INTERVAL_MS,
                SNAPSHOT_SETTLE_DELAY_MS);
        mainHandler.postDelayed(pendingSnapshot, delay);
    }

    private String describeEvent(
            AccessibilityEvent event,
            AccessibilityNodeInfo source) {
        return "event=RECEIVED"
                + ", receivedAtEpochMs=" + System.currentTimeMillis()
                + ", eventTimeMs=" + event.getEventTime()
                + ", eventType=" + event.getEventType()
                + ", eventTypeName=" + ProbePrivacyPolicy.cleanMetadata(
                        AccessibilityEvent.eventTypeToString(event.getEventType()))
                + ", packageName=" + ProbePrivacyPolicy.cleanMetadata(event.getPackageName())
                + ", className=" + ProbePrivacyPolicy.cleanMetadata(event.getClassName())
                + ", windowId=" + event.getWindowId()
                + ", action=" + event.getAction()
                + ", contentChangeTypes=" + event.getContentChangeTypes()
                + ", eventText=" + ProbePrivacyPolicy.summarizeTextList(event.getText())
                + ", eventContentDescription="
                + ProbePrivacyPolicy.summarizeText(event.getContentDescription())
                + ", source=" + describeSource(source);
    }

    private void snapshotActiveWindow(String triggerPackage, int triggerEventType) {
        long snapshotId = System.currentTimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String rootPackage = root == null
                ? null
                : ProbePrivacyPolicy.cleanMetadata(root.getPackageName());
        boolean persist = ProbePrivacyPolicy.shouldPersistSnapshot(rootPackage);
        recordInfo("windowSnapshot=BEGIN"
                + ", snapshotId=" + snapshotId
                + ", triggerPackage=" + triggerPackage
                + ", triggerEventType=" + triggerEventType
                + ", rootAvailable=" + (root != null), persist);
        if (root == null) {
            recordInfo("windowSnapshot=END, snapshotId=" + snapshotId + ", nodeCount=0",
                    persist);
            return;
        }

        int[] count = {0};
        try {
            logNode(snapshotId, root, 0, count, persist);
        } finally {
            root.recycle();
        }
        recordInfo("windowSnapshot=END"
                + ", snapshotId=" + snapshotId
                + ", nodeCount=" + count[0]
                + ", truncated=" + (count[0] >= MAX_SNAPSHOT_NODES), persist);
    }

    private void logNode(
            long snapshotId,
            AccessibilityNodeInfo node,
            int depth,
            int[] count,
            boolean persist) {
        if (node == null
                || depth > MAX_SNAPSHOT_DEPTH
                || count[0] >= MAX_SNAPSHOT_NODES) {
            return;
        }

        int index = count[0]++;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String textSummary = node.isPassword()
                ? "{present=true,redacted=password}"
                : ProbePrivacyPolicy.summarizeText(node.getText());
        String descriptionSummary = node.isPassword()
                ? "{present=true,redacted=password}"
                : ProbePrivacyPolicy.summarizeText(node.getContentDescription());
        recordInfo("windowNode=REDACTED"
                + ", snapshotId=" + snapshotId
                + ", index=" + index
                + ", depth=" + depth
                + ", packageName=" + ProbePrivacyPolicy.cleanMetadata(node.getPackageName())
                + ", className=" + ProbePrivacyPolicy.cleanMetadata(node.getClassName())
                + ", viewId=" + ProbePrivacyPolicy.cleanMetadata(node.getViewIdResourceName())
                + ", textSummary=" + textSummary
                + ", contentDescriptionSummary=" + descriptionSummary
                + ", childCount=" + node.getChildCount()
                + ", visible=" + node.isVisibleToUser()
                + ", clickable=" + node.isClickable()
                + ", enabled=" + node.isEnabled()
                + ", focusable=" + node.isFocusable()
                + ", scrollable=" + node.isScrollable()
                + ", password=" + node.isPassword()
                + ", bounds=" + bounds.toShortString(), persist);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && count[0] < MAX_SNAPSHOT_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                logNode(snapshotId, child, depth + 1, count, persist);
            } finally {
                child.recycle();
            }
        }
    }

    private String describeSource(AccessibilityNodeInfo source) {
        if (source == null) {
            return "null";
        }
        Rect bounds = new Rect();
        source.getBoundsInScreen(bounds);
        return "{packageName=" + ProbePrivacyPolicy.cleanMetadata(source.getPackageName())
                + ", className=" + ProbePrivacyPolicy.cleanMetadata(source.getClassName())
                + ", viewId=" + ProbePrivacyPolicy.cleanMetadata(source.getViewIdResourceName())
                + ", textSummary=" + (source.isPassword()
                ? "{present=true,redacted=password}"
                : ProbePrivacyPolicy.summarizeText(source.getText()))
                + ", contentDescriptionSummary=" + (source.isPassword()
                ? "{present=true,redacted=password}"
                : ProbePrivacyPolicy.summarizeText(source.getContentDescription()))
                + ", childCount=" + source.getChildCount()
                + ", visible=" + source.isVisibleToUser()
                + ", clickable=" + source.isClickable()
                + ", enabled=" + source.isEnabled()
                + ", focusable=" + source.isFocusable()
                + ", scrollable=" + source.isScrollable()
                + ", password=" + source.isPassword()
                + ", bounds=" + bounds.toShortString()
                + "}";
    }

    private void recordInfo(String message, boolean persistOffline) {
        Log.i(TAG, message);
        if (!persistOffline) {
            return;
        }
        appendOfflineRecord(message);
    }

    private synchronized void appendOfflineRecord(String message) {
        File logFile = new File(getFilesDir(), OFFLINE_LOG_NAME);
        byte[] record = (System.currentTimeMillis() + " " + message + '\n')
                .getBytes(StandardCharsets.UTF_8);
        boolean append = logFile.length() + record.length <= MAX_OFFLINE_LOG_BYTES;
        try (FileOutputStream output = new FileOutputStream(logFile, append)) {
            if (!append) {
                output.write((System.currentTimeMillis()
                        + " offlineLog=ROTATED, privacyMode=STRUCTURED_REDACTION\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            output.write(record);
        } catch (IOException error) {
            Log.w(TAG, "offlineLog=WRITE_FAILED, error="
                    + error.getClass().getSimpleName());
        }
    }
}
