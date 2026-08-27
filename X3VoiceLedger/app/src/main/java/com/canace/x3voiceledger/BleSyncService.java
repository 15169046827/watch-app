package com.canace.x3voiceledger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import com.canace.x3voiceledger.protocol.SyncProtocol;

import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class BleSyncService extends Service {
    private static final String CHANNEL = "watch_ledger_sync";
    private static final int NOTIFICATION_ID = 3101;
    private static final long ADVERTISE_WINDOW_MS = 180_000L;
    private static final String ACTION_CONFIG_WINDOW = "com.canace.x3voiceledger.CONFIG_WINDOW";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LedgerStore store;
    private WatchProfileStore profileStore;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private LedgerEntry activeEntry;
    private byte[] activePayload;
    private boolean configWindow;
    private ByteArrayOutputStream configBuffer;
    private int configNextChunk;
    private int configChunkCount;
    private int configTotalLength;

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, BleSyncService.class));
        } catch (RuntimeException ignored) {
            WatchRetryJobService.schedule(context);
        }
    }

    static void startConfigWindow(Context context) {
        try {
            context.startForegroundService(new Intent(context, BleSyncService.class)
                    .setAction(ACTION_CONFIG_WINDOW));
        } catch (RuntimeException ignored) {
            WatchRetryJobService.schedule(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new LedgerStore(this);
        profileStore = new WatchProfileStore(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("准备蓝牙同步…"));
        WatchRetryJobService.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CONFIG_WINDOW.equals(intent.getAction())) configWindow = true;
        if (store.pendingCount() == 0 && !configWindow) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (gattServer == null) {
            startGatt();
        } else {
            updateWaitingNotification();
        }
        return START_NOT_STICKY;
    }

    private void startGatt() {
        stopBluetooth();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            stopWithMessage("蓝牙未开启，稍后重试");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            stopWithMessage("手表不支持 BLE 广播");
            return;
        }
        gattServer = manager.openGattServer(this, gattCallback);
        if (gattServer == null) {
            stopWithMessage("无法启动账单同步服务");
            return;
        }

        BluetoothGattService service = new BluetoothGattService(SyncProtocol.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic data = new BluetoothGattCharacteristic(SyncProtocol.DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM);
        BluetoothGattCharacteristic ack = new BluetoothGattCharacteristic(SyncProtocol.ACK_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM);
        BluetoothGattCharacteristic config = new BluetoothGattCharacteristic(SyncProtocol.CONFIG_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM);
        service.addCharacteristic(data);
        service.addCharacteristic(ack);
        service.addCharacteristic(config);
        if (!gattServer.addService(service)) stopWithMessage("无法注册同步协议");
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising();
            else stopWithMessage("同步服务注册失败：" + status);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) updateNotification("手机已连接，正在传输…");
            else if (newState == BluetoothProfile.STATE_DISCONNECTED
                    && (store.pendingCount() > 0 || configWindow)) {
                activeEntry = null;
                activePayload = null;
                updateWaitingNotification();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (!SyncProtocol.DATA_UUID.equals(characteristic.getUuid())) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset, null);
                return;
            }
            if (offset == 0 || activePayload == null) loadNextPayload();
            if (activePayload == null || offset < 0 || offset > activePayload.length) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] part = Arrays.copyOfRange(activePayload, offset, activePayload.length);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part);
            if (activeEntry == null) handler.postDelayed(BleSyncService.this::stopSelf, 800L);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            int status = BluetoothGatt.GATT_FAILURE;
            if (SyncProtocol.CONFIG_UUID.equals(characteristic.getUuid()) && !preparedWrite && offset == 0) {
                status = receiveConfigChunk(value) ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
            } else if (SyncProtocol.ACK_UUID.equals(characteristic.getUuid()) && !preparedWrite && offset == 0
                    && activeEntry != null) {
                String ackId = new String(value, StandardCharsets.UTF_8);
                if (activeEntry.id.equals(ackId) && store.markSynced(ackId)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    activeEntry = null;
                    activePayload = null;
                    updateNotification("已同步，剩余 " + store.pendingCount() + " 笔");
                }
            }
            if (responseNeeded) gattServer.sendResponse(device, requestId, status, offset, null);
        }
    };

    private boolean receiveConfigChunk(byte[] value) {
        if (value == null || value.length < 10 || value[0] != 0x43 || value[1] != 1) return false;
        int index = unsignedShort(value, 2);
        int count = unsignedShort(value, 4);
        int total = ((value[6] & 0xff) << 24) | ((value[7] & 0xff) << 16)
                | ((value[8] & 0xff) << 8) | (value[9] & 0xff);
        if (index == 0) {
            if (count <= 0 || total <= 0 || total > 64_000) return false;
            configBuffer = new ByteArrayOutputStream(total);
            configNextChunk = 0;
            configChunkCount = count;
            configTotalLength = total;
        }
        if (configBuffer == null || index != configNextChunk || count != configChunkCount
                || total != configTotalLength) return false;
        configBuffer.write(value, 10, value.length - 10);
        configNextChunk++;
        if (configBuffer.size() > configTotalLength) return false;
        if (configNextChunk == configChunkCount) {
            boolean complete = configBuffer.size() == configTotalLength
                    && profileStore.apply(configBuffer.toByteArray());
            configBuffer = null;
            if (!complete) return false;
            configWindow = false;
            updateNotification("已接收手机账户与分类配置");
        }
        return true;
    }

    private static int unsignedShort(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    private void loadNextPayload() {
        activeEntry = store.nextPending();
        if (activeEntry == null) {
            activePayload = LedgerPayloadCodec.empty();
            return;
        }
        try {
            activePayload = LedgerPayloadCodec.ledger(activeEntry);
        } catch (JSONException error) {
            activeEntry = null;
            activePayload = LedgerPayloadCodec.empty();
        }
    }

    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SyncProtocol.SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build();
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
            handler.postDelayed(() -> {
                if (store.pendingCount() > 0 || configWindow) stopWithMessage("本次未发现手机，稍后重试");
                else stopSelf();
            }, ADVERTISE_WINDOW_MS);
        } catch (RuntimeException error) {
            stopWithMessage("BLE 广播启动失败");
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            updateWaitingNotification();
        }

        @Override
        public void onStartFailure(int errorCode) {
            stopWithMessage("BLE 广播失败：" + errorCode);
        }
    };

    private void updateWaitingNotification() {
        int pending = store.pendingCount();
        updateNotification(pending == 0 ? "等待手机发送账户与分类配置"
                : "等待手机自动接收（" + pending + " 笔）");
    }

    private void stopWithMessage(String message) {
        updateNotification(message);
        handler.postDelayed(this::stopSelf, 1500L);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopBluetooth();
        store.close();
        super.onDestroy();
    }

    private void stopBluetooth() {
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (RuntimeException ignored) { }
            advertiser = null;
        }
        if (gattServer != null) {
            gattServer.clearServices();
            gattServer.close();
            gattServer = null;
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "账单蓝牙同步",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("记账")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
