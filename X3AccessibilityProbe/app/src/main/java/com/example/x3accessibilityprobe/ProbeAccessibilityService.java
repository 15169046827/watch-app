package com.example.x3accessibilityprobe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProbeAccessibilityService extends AccessibilityService {

    private static final String TAG = "X3AccessibilityProbe";
    private static final long SNAPSHOT_INTERVAL_MS = 750L;
    private static final int MAX_SNAPSHOT_NODES = 80;
    private static final int MAX_SNAPSHOT_DEPTH = 12;
    private static final int MAX_METADATA_LENGTH = 180;
    private static final long SNAPSHOT_SETTLE_DELAY_MS = 250L;
    private static final long MAX_OFFLINE_LOG_BYTES = 512L * 1024L;
    private static final String OFFLINE_LOG_NAME = "payment-probe-sanitized.log";
    private static final String[] PAYMENT_KEYWORDS = {
            "支付成功", "付款成功", "交易成功", "支付完成", "付款完成",
            "支付失败", "付款失败", "已支付", "已付款", "支付", "付款",
            "收款", "微信", "支付宝", "人民币", "RMB", "¥", "￥", "元"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastSnapshotElapsedMs;
    private boolean snapshotScheduled;

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
            String packageName = cleanMetadata(event.getPackageName());
            recordInfo(describeEvent(event, source), isPaymentPackage(packageName));
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
        snapshotScheduled = false;
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
        if (snapshotScheduled || now - lastSnapshotElapsedMs < SNAPSHOT_INTERVAL_MS) {
            return;
        }
        String triggerPackage = cleanMetadata(event.getPackageName());
        int triggerEventType = type;
        snapshotScheduled = true;
        mainHandler.postDelayed(() -> {
            snapshotScheduled = false;
            lastSnapshotElapsedMs = SystemClock.elapsedRealtime();
            snapshotActiveWindow(triggerPackage, triggerEventType);
        }, SNAPSHOT_SETTLE_DELAY_MS);
    }

    private String describeEvent(
            AccessibilityEvent event,
            AccessibilityNodeInfo source) {
        return "event=RECEIVED"
                + ", receivedAtEpochMs=" + System.currentTimeMillis()
                + ", eventTimeMs=" + event.getEventTime()
                + ", eventType=" + event.getEventType()
                + ", eventTypeName=" + cleanMetadata(
                AccessibilityEvent.eventTypeToString(event.getEventType()))
                + ", packageName=" + cleanMetadata(event.getPackageName())
                + ", className=" + cleanMetadata(event.getClassName())
                + ", windowId=" + event.getWindowId()
                + ", action=" + event.getAction()
                + ", contentChangeTypes=" + event.getContentChangeTypes()
                + ", eventText=" + summarizeTextList(event.getText())
                + ", eventContentDescription=" + summarizeText(event.getContentDescription())
                + ", source=" + describeSource(source);
    }

    private void snapshotActiveWindow(String triggerPackage, int triggerEventType) {
        long snapshotId = System.currentTimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean persist = isPaymentPackage(triggerPackage)
                || (root != null && isPaymentPackage(cleanMetadata(root.getPackageName())));
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
                : summarizeText(node.getText());
        String descriptionSummary = node.isPassword()
                ? "{present=true,redacted=password}"
                : summarizeText(node.getContentDescription());
        recordInfo("windowNode=REDACTED"
                + ", snapshotId=" + snapshotId
                + ", index=" + index
                + ", depth=" + depth
                + ", packageName=" + cleanMetadata(node.getPackageName())
                + ", className=" + cleanMetadata(node.getClassName())
                + ", viewId=" + cleanMetadata(node.getViewIdResourceName())
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
        return "{packageName=" + cleanMetadata(source.getPackageName())
                + ", className=" + cleanMetadata(source.getClassName())
                + ", viewId=" + cleanMetadata(source.getViewIdResourceName())
                + ", textSummary=" + (source.isPassword()
                ? "{present=true,redacted=password}" : summarizeText(source.getText()))
                + ", contentDescriptionSummary=" + (source.isPassword()
                ? "{present=true,redacted=password}"
                : summarizeText(source.getContentDescription()))
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

    private String summarizeTextList(List<CharSequence> values) {
        if (values == null || values.isEmpty()) {
            return "{count=0}";
        }
        StringBuilder result = new StringBuilder("{count=")
                .append(values.size())
                .append(",items=[");
        int limit = Math.min(values.size(), 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(summarizeText(values.get(i)));
        }
        if (values.size() > limit) {
            result.append(",…");
        }
        return result.append("]}").toString();
    }

    private String summarizeText(Object value) {
        if (value == null) {
            return "null";
        }
        String normalized = String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        if (TextUtils.isEmpty(normalized)) {
            return "{present=false}";
        }

        int digitCount = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isDigit(normalized.charAt(i))) {
                digitCount++;
            }
        }
        boolean hasCurrency = normalized.contains("¥")
                || normalized.contains("￥")
                || normalized.contains("人民币")
                || normalized.toUpperCase().contains("RMB");
        boolean decimalAmountLike = normalized.matches(".*\\d+[.,]\\d{1,2}.*");

        StringBuilder keywords = new StringBuilder("[");
        boolean first = true;
        for (String keyword : PAYMENT_KEYWORDS) {
            if (!normalized.contains(keyword)) {
                continue;
            }
            if (!first) {
                keywords.append('|');
            }
            keywords.append(keyword);
            first = false;
        }
        keywords.append(']');

        return "{present=true"
                + ",length=" + normalized.length()
                + ",digitCount=" + digitCount
                + ",hasCurrency=" + hasCurrency
                + ",decimalAmountLike=" + decimalAmountLike
                + ",paymentKeywords=" + keywords
                + "}";
    }

    private String cleanMetadata(Object value) {
        if (value == null) {
            return "null";
        }
        String cleaned = String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ');
        if (cleaned.length() > MAX_METADATA_LENGTH) {
            return cleaned.substring(0, MAX_METADATA_LENGTH) + "…";
        }
        return cleaned;
    }

    private boolean isPaymentPackage(String packageName) {
        return "com.eg.android.AlipayGphone".equals(packageName)
                || "com.tencent.watchwechat".equals(packageName)
                || "com.heytap.wearable.wallet".equals(packageName);
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
