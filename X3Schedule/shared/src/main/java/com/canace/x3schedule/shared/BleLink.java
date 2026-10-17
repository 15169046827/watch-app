package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import com.canace.x3schedule.domain.Batch;
import com.canace.x3schedule.domain.BatchExchange;
import com.canace.x3schedule.domain.Frames;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/**
 * Foreground MVP transport. Explicitly selected bonded peer, encrypted ATT, no discovery scan.
 */
// Runtime permission gate and SecurityException boundary cover every BLE callback.
@android.annotation.SuppressLint("MissingPermission")
public final class BleLink implements AutoCloseable {

    private final UUID serviceId;

    private final UUID dataId;

    private final Context context;

    private final Store store;

    private final Runnable changed;

    private final Runnable reminders;

    private static final HandlerThread WORKER = startWorker();

    private final Handler handler = new Handler(WORKER.getLooper());

    private final Handler ui = new Handler(Looper.getMainLooper());

    private BatchExchange exchange = new BatchExchange();

    private BluetoothGatt client;

    private BluetoothGattServer server;

    private BluetoothGattCharacteristic characteristic;

    private BluetoothLeAdvertiser advertiser;

    private BluetoothAdapter adapter;

    private volatile boolean closed;

    private Frames rx = new Frames();

    private final ArrayDeque<byte[]> response = new ArrayDeque<>();

    private int responseOffset;

    private int txOffset;

    private int chunkSize = 20;

    private byte[] tx;

    private boolean finalAck;

    private String peer;

    public volatile String status = "未连接；对方的新记录未知";

    public BleLink(Context c, Store s, Runnable changed) {
        this(c, s, changed, () -> ReminderReceiver.schedule(c, false));
    }

    // Synthetic transport checks supply an isolated callback instead of opening the main database.
    BleLink(Context c, Store s, Runnable changed, Runnable reminders) {
        context = c;
        store = s;
        this.changed = changed;
        this.reminders = reminders;
        serviceId = UUID.fromString(c.getString(R.string.schedule_ble_service));
        dataId = UUID.fromString(c.getString(R.string.schedule_ble_data));
    }

    private static HandlerThread startWorker() {
        HandlerThread thread = new HandlerThread("schedule-ble");
        thread.start();
        return thread;
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 || (context.checkSelfPermission(android.Manifest.permission
                .BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED && context
                .checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm
                .PackageManager.PERMISSION_GRANTED);
    }

    private void post(Runnable action) {
        handler.post(() -> {
            if (closed) {
                return;
            }
            if (!permitted()) {
                status("需要蓝牙权限");
                return;
            }
            try {
                action.run();
            } catch (SecurityException e) {
                status("蓝牙权限已撤销");
            } catch (RuntimeException e) {
                status("连接未完成，数据保留待重试");
            }
        });
    }

    private void status(String s) {
        status = s;
        ui.post(() -> {
            if (!closed) {
                changed.run();
            }
        });
    }

    public void start() {
        post(this::startInternal);
    }

    private void startInternal() {
        if (!permitted()) {
            status("需要蓝牙权限");
            return;
        }
        peer = store.meta("peer", "");
        if (peer.isEmpty()) {
            status("请先选择已配对设备");
            return;
        }
        try {
            adapter = context.getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                status("蓝牙未开启");
                return;
            }
            if (store.phone) {
                connect();
            } else {
                listen();
            }
        } catch (SecurityException e) {
            status("需要蓝牙权限");
        } catch (RuntimeException e) {
            status("蓝牙暂不可用");
        }
    }

    private boolean trusted(BluetoothDevice d) {
        return d != null && d.getAddress().equals(peer) && d.getBondState() == BluetoothDevice.BOND_BONDED;
    }

    private void connect() {
        if (closed) {
            return;
        }
        BluetoothDevice d = adapter.getRemoteDevice(peer);
        if (!trusted(d)) {
            status("请在系统蓝牙设置完成配对");
            return;
        }
        status("正在连接");
        client = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        handler.postDelayed(timeout, 45000);
    }

    private final Runnable timeout = () -> post(() -> retry("连接中断，将自动重试"));

    private void retry(String s) {
        if (closed) {
            return;
        }
        handler.removeCallbacks(timeout);
        if (client != null) {
            client.disconnect();
            client.close();
            client = null;
        }
        rx = new Frames();
        status(s);
        handler.postDelayed(() -> post(() -> {
            if (client == null) {
                connect();
            }
        }), 15000);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int code, int state) {
            post(() -> {
                if (g != client || closed) {
                    return;
                }
                if (code == 0 && state == BluetoothProfile.STATE_CONNECTED) {
                    chunkSize = 20;
                    if (!g.requestMtu(185) && !g.discoverServices()) {
                        retry("服务发现失败");
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    retry("未连接；对方的新记录未知");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int code) {
            post(() -> {
                if (g != client) {
                    return;
                }
                chunkSize = code == 0 ? Math.max(20, Math.min(180, mtu - 3)) : 20;
                if (!g.discoverServices()) {
                    retry("服务发现失败");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int code) {
            post(() -> {
                if (g != client) {
                    return;
                }
                try {
                    BluetoothGattService s = g.getService(serviceId);
                    require(code == 0 && s != null, "service");
                    characteristic = s.getCharacteristic(dataId);
                    require(characteristic != null, "characteristic");
                    exchange = new BatchExchange();
                    send(Frames.pack(3, store.outgoingBatch()), false);
                } catch (RuntimeException e) {
                    retry("同步服务不可用");
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            post(() -> {
                if (g != client) {
                    return;
                }
                handler.removeCallbacks(timeout);
                handler.postDelayed(timeout, 45000);
                if (code != 0) {
                    retry("发送失败，记录保留待重试");
                    return;
                }
                if (txOffset < tx.length) {
                    writeNext();
                } else if (finalAck) {
                    nextRound();
                } else if (!g.readCharacteristic(characteristic)) {
                    retry("读取失败");
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            byte[] value = c.getValue();
            post(() -> {
                if (g != client) {
                    return;
                }
                handler.removeCallbacks(timeout);
                handler.postDelayed(timeout, 45000);
                try {
                    require(code == 0 && value != null && value.length > 0, "read");
                    byte[] frame = rx.add(value);
                    if (frame == null) {
                        require(g.readCharacteristic(characteristic), "read");
                        return;
                    }
                    byte[] body = Frames.body(frame);
                    if (frame[0] == 4) {
                        exchange.localAck(store.acknowledgeBatch(Batch.decodeAck(body)));
                        require(g.readCharacteristic(characteristic), "read");
                    } else {
                        require(frame[0] == 3, "请将两端升级到 0.7.0 或兼容版本");
                        Batch.Ack ack = store.receiveBatch(body);
                        exchange.remoteAck(ack.committed);
                        send(Frames.pack(4, Batch.encodeAck(ack)), true);
                    }
                } catch (RuntimeException e) {
                    retry("同步校验失败，原数据保留");
                }
            });
        }
    };

    private void nextRound() {
        int kind = exchange.nextKind(store.pending() > 0);
        if (kind != 0) {
            status("正在分批同步，已接收部分会保留进度");
            send(Frames.pack(kind, kind == 3 ? store.outgoingBatch() : new byte[0]), false);
            return;
        }
        handler.removeCallbacks(timeout);
        status(store.pending() == 0 ? "对方已确认本机数据" : "仍有新改动待同步");
        scheduleReminders();
        handler.postDelayed(() -> post(() -> retry("正在检查新改动")), 60000);
    }

    private void send(byte[] frame, boolean ack) {
        tx = frame;
        txOffset = 0;
        finalAck = ack;
        writeNext();
    }

    private void writeNext() {
        int end = Math.min(tx.length, txOffset + chunkSize);
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(Arrays.copyOfRange(tx, txOffset, end));
        txOffset = end;
        if (!client.writeCharacteristic(characteristic)) {
            retry("发送忙，将自动重试");
        }
    }

    private final AdvertiseCallback advertising = new AdvertiseCallback() {

        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            status("等待已配对手机；离线查看与记录可用");
        }

        @Override
        public void onStartFailure(int code) {
            status("蓝牙广播不可用，请重新打开应用");
        }
    };

    private void listen() {
        server = context.getSystemService(BluetoothManager.class).openGattServer(context, serverCallback);
        require(server != null, "server");
        BluetoothGattService service = new BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(dataId, BluetoothGattCharacteristic
                .PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic
                .PERMISSION_READ_ENCRYPTED | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED));
        require(server.addService(service), "service");
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {

        @Override
        public void onMtuChanged(BluetoothDevice d, int mtu) {
            post(() -> {
                if (trusted(d)) {
                    chunkSize = Math.max(20, Math.min(180, mtu - 3));
                }
            });
        }

        @Override
        public void onServiceAdded(int code, BluetoothGattService s) {
            post(() -> {
                if (closed || code != 0) {
                    return;
                }
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser == null) {
                    status("设备不支持广播");
                    return;
                }
                advertiser.startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings
                        .ADVERTISE_MODE_LOW_POWER).setConnectable(true).build(), new AdvertiseData.Builder()
                        .addServiceUuid(new ParcelUuid(serviceId)).build(), advertising);
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice d, int code, int state) {
            post(() -> {
                if (closed) {
                    return;
                }
                if (!trusted(d)) {
                    server.cancelConnection(d);
                    return;
                }
                chunkSize = 20;
                rx = new Frames();
                response.clear();
                responseOffset = 0;
                status(state == BluetoothProfile.STATE_CONNECTED ? "正在同步" : "未连接；离线查看与记录可用");
            });
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice d, int id, BluetoothGattCharacteristic c,
                boolean prepared, boolean needResponse, int offset, byte[] value) {
            post(() -> {
                if (closed) {
                    return;
                }
                int result = BluetoothGatt.GATT_SUCCESS;
                try {
                    require(trusted(d) && !prepared && offset == 0 && value != null, "peer");
                    byte[] frame = rx.add(value);
                    if (frame != null) {
                        byte[] body = Frames.body(frame);
                        serverFrame(frame[0], body);
                    }
                } catch (RuntimeException e) {
                    rx = new Frames();
                    result = BluetoothGatt.GATT_FAILURE;
                    status("同步未完成，数据保留");
                }
                if (needResponse) {
                    server.sendResponse(d, id, result, 0, null);
                }
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice d, int id, int offset, BluetoothGattCharacteristic c) {
            post(() -> {
                if (closed) {
                    return;
                }
                if (!trusted(d) || offset != 0 || response.isEmpty()) {
                    server.sendResponse(d, id, BluetoothGatt.GATT_FAILURE, 0, null);
                    return;
                }
                byte[] head = response.peek();
                int end = Math.min(head.length, responseOffset + chunkSize);
                byte[] chunk = Arrays.copyOfRange(head, responseOffset, end);
                if (server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, chunk)) {
                    responseOffset = end;
                    if (end == head.length) {
                        response.remove();
                        responseOffset = 0;
                    }
                }
            });
        }
    };

    private void scheduleReminders() {
        try {
            reminders.run();
        } catch (RuntimeException error) {
            status("数据已合并，提醒安排待重试");
        }
    }

    private void serverFrame(int kind, byte[] body) {
        if (kind == 4) {
            boolean committed = store.acknowledgeBatch(Batch.decodeAck(body));
            status(committed ? "对方已确认本机数据" : "正在分批同步");
            return;
        }
        require(kind == 3 || (kind == 5 && body.length == 0), "请将两端升级到 0.7.0 或兼容版本");
        response.clear();
        responseOffset = 0;
        if (kind == 3) {
            Batch.Ack ack = store.receiveBatch(body);
            response.add(Frames.pack(4, Batch.encodeAck(ack)));
            if (ack.committed) {
                scheduleReminders();
            }
        }
        response.add(Frames.pack(3, store.outgoingBatch()));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        handler.removeCallbacksAndMessages(null);
        // One process worker serializes old-link cleanup before a replacement starts.
        handler.post(this::closeBluetooth);
    }

    private void closeBluetooth() {
        try {
            if (client != null) {
                client.disconnect();
                client.close();
            }
            if (advertiser != null) {
                advertiser.stopAdvertising(advertising);
            }
            if (server != null) {
                server.close();
            }
        } catch (SecurityException ignored) {
            /* Revocation is terminal; persisted queues survive. */
        }
    }

    void closeAndDrain(Runnable drained) {
        close();
        handler.post(() -> ui.post(drained));
    }
}
