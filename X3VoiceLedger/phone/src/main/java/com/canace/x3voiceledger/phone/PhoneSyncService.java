package com.canace.x3voiceledger.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;

public final class PhoneSyncService extends Service {
    private static final String CHANNEL = "ledger_sync";
    private static final int NOTIFICATION_ID = 2201;
    private static final long RETRY_DELAY_MS = 30_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BleCentralClient client;
    private PhoneLedgerStore store;
    private LedgerProfileStore profileStore;
    private WatchScreenshotMonitor screenshotMonitor;

    static void start(Context context) {
        Intent intent = new Intent(context, PhoneSyncService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            // A later foreground app launch will retry.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        store = new PhoneLedgerStore(this);
        profileStore = new LedgerProfileStore(this);
        startForeground(NOTIFICATION_ID, notification("准备自动同步…"));
        screenshotMonitor =
                new WatchScreenshotMonitor(
                        this,
                        store,
                        profileStore,
                        new WatchScreenshotMonitor.Callback() {
                        @Override
                        public void onRecognized(long amountCents, String account) {
                            update(
                                    "识别到手表付款 ¥"
                                            + String.format(
                                                    java.util.Locale.CHINA,
                                                    "%.2f",
                                                    amountCents / 100d)
                                            + "，请打开伴侣确认");
                        }

                        @Override
                        public void onMessage(String message) {
                            update(message);
                        }
                    });
        screenshotMonitor.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (screenshotMonitor != null) {
            screenshotMonitor.start();
        }
        startClient();
        return START_STICKY;
    }

    private void startClient() {
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.stop();
        }
        client =
                new BleCentralClient(
                        this,
                        new BleCentralClient.Callback() {
                        @Override
                        public void onState(String state) {
                            update(state);
                        }

                        @Override
                        public void onLedger(JSONObject payload) {
                            store.upsert(payload);
                            update("已接收账单，正在确认…");
                        }

                        @Override
                        public void onFinished() {
                            update("自动同步已开启，等待新账单");
                            handler.postDelayed(
                                    PhoneSyncService.this::startClient, RETRY_DELAY_MS);
                        }

                        @Override
                        public void onError(String message) {
                            update(message);
                            handler.postDelayed(
                                    PhoneSyncService.this::startClient, RETRY_DELAY_MS);
                        }
                    },
                        profileStore.payload());
        client.start();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.stop();
        }
        if (screenshotMonitor != null) {
            screenshotMonitor.close();
        }
        if (store != null) {
            store.close();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationChannel channel =
                new NotificationChannel(CHANNEL, "手表账单同步", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, PhoneMainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("X3 账单接收")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void update(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }
}
