package com.canace.x3voiceledger;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AlipayPaymentAccessibilityService extends AccessibilityService {
    static final String EXTRA_EDIT_ENTRY_ID = "edit_entry_id";
    private static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";
    private static final String SUCCESS_ACTIVITY = "com.alipay.watch.npay.ui.NFCPaySuccessActivity";
    private static final String CHANNEL = "automatic_payment_drafts";
    private static final int NOTIFICATION_ID = 3201;
    private static final long DEDUP_WINDOW_MS = 30_000L;
    private static final String PREFS = "automatic_payment_dedup";
    private boolean successWindowActive;
    private boolean capturedCurrentWindow;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        boolean fromAlipay = ALIPAY_PACKAGE.contentEquals(packageName == null ? "" : packageName);
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            successWindowActive = fromAlipay
                    && SUCCESS_ACTIVITY.contentEquals(className == null ? "" : className);
            if (!successWindowActive) capturedCurrentWindow = false;
        }
        if (!fromAlipay || !successWindowActive || capturedCurrentWindow) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<AlipayPaymentParser.Node> nodes = new ArrayList<>();
        try {
            collect(root, nodes);
        } finally {
            root.recycle();
        }
        AlipayPaymentParser.Result result = AlipayPaymentParser.parse(nodes);
        if (result == null) return;
        capturedCurrentWindow = true;
        long now = System.currentTimeMillis();
        if (isRecentDuplicate(result.amountCents, now)) return;
        saveDraft(result.amountCents, now);
    }

    private void collect(AccessibilityNodeInfo node, List<AlipayPaymentParser.Node> result) {
        CharSequence text = node.getText();
        result.add(new AlipayPaymentParser.Node(node.getViewIdResourceName(),
                text == null ? "" : text.toString()));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collect(child, result);
            } finally {
                child.recycle();
            }
        }
    }

    private boolean isRecentDuplicate(long amountCents, long now) {
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long previousAmount = preferences.getLong("amount", -1L);
        long previousTime = preferences.getLong("time", 0L);
        if (previousAmount == amountCents && now - previousTime >= 0 && now - previousTime < DEDUP_WINDOW_MS) {
            return true;
        }
        preferences.edit().putLong("amount", amountCents).putLong("time", now).apply();
        return false;
    }

    private void saveDraft(long amountCents, long now) {
        WatchProfileStore profile = new WatchProfileStore(this);
        String note = "支付宝 NFC 支付（待补充商户）";
        WatchProfileStore.CategorySelection category = profile.categoryFor("其他", note);
        LedgerEntry entry = new LedgerEntry(
                UUID.randomUUID().toString(), now, now, now, amountCents,
                note, "其他", category.parent, category.child, profile.inferAccount("支付宝"),
                profile.bookName(), profile.tags(), "支付宝 NFC 自动识别", "alipay_nfc_accessibility",
                null, null, null, null, "");
        try (LedgerStore store = new LedgerStore(this)) {
            store.insert(entry);
        }
        BleSyncService.start(this);
        WatchRetryJobService.schedule(this);
        showDraftNotification(entry.id, amountCents);
    }

    private void showDraftNotification(String id, long amountCents) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "自动识别账单",
                NotificationManager.IMPORTANCE_HIGH));
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_EDIT_ENTRY_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("已识别支付宝付款 " + LedgerParser.formatAmount(amountCents))
                .setContentText("点击补充商户、分类和账户")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    @Override public void onInterrupt() { }
}
