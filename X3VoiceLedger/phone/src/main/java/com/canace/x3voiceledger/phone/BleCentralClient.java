package com.canace.x3voiceledger.phone;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import com.canace.x3voiceledger.protocol.SyncProtocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

@SuppressLint("MissingPermission")
final class BleCentralClient {
    interface Callback {
        void onState(String state);
        void onLedger(JSONObject payload);
        void onFinished();
        void onError(String message);
    }

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic ackCharacteristic;
    private BluetoothGattCharacteristic configCharacteristic;
    private final byte[] configPayload;
    private int negotiatedMtu = 23;
    private int configOffset;
    private int configChunkIndex;
    private int configChunkCount;
    private String pendingAck;
    private boolean stopped;

    BleCentralClient(Context context, Callback callback, byte[] configPayload) {
        this.context = context;
        this.callback = callback;
        this.configPayload = configPayload == null ? new byte[0] : configPayload;
    }

    void start() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            callback.onError("蓝牙未开启");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            callback.onError("手机不支持 BLE 扫描");
            return;
        }
        stopped = false;
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SyncProtocol.SERVICE_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        callback.onState("正在查找手表…");
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            handler.postDelayed(() -> {
                if (gatt == null) fail("未发现手表，稍后自动重试");
            }, 60_000L);
        } catch (SecurityException error) {
            fail("缺少附近设备或定位权限");
        }
    }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) { }
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) { }
            gatt = null;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (stopped || gatt != null) return;
            try {
                scanner.stopScan(this);
                BluetoothDevice device = result.getDevice();
                callback.onState("已发现手表，正在连接…");
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException error) {
                fail("连接权限不足");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            fail("BLE 扫描失败：" + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("连接失败：" + status);
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                callback.onState("已连接，正在读取账单…");
                bluetoothGatt.requestMtu(517);
                bluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !stopped) {
                fail("手表连接已断开");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            BluetoothGattService service = bluetoothGatt.getService(SyncProtocol.SERVICE_UUID);
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                fail("手表同步服务不可用");
                return;
            }
            dataCharacteristic = service.getCharacteristic(SyncProtocol.DATA_UUID);
            ackCharacteristic = service.getCharacteristic(SyncProtocol.ACK_UUID);
            configCharacteristic = service.getCharacteristic(SyncProtocol.CONFIG_UUID);
            if (dataCharacteristic == null || ackCharacteristic == null || configCharacteristic == null) {
                fail("同步协议不匹配");
                return;
            }
            beginConfigTransfer(bluetoothGatt);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt bluetoothGatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                        ? "请先在系统蓝牙中配对手表" : "读取账单失败：" + status);
                return;
            }
            try {
                JSONObject payload = new JSONObject(new String(characteristic.getValue(), StandardCharsets.UTF_8));
                if (SyncProtocol.TYPE_EMPTY.equals(payload.optString("type"))) {
                    callback.onState("同步完成");
                    callback.onFinished();
                    stop();
                    return;
                }
                if (!SyncProtocol.TYPE_LEDGER.equals(payload.optString("type"))) {
                    fail("未知同步数据");
                    return;
                }
                callback.onLedger(payload);
                pendingAck = payload.getString("id");
                ackCharacteristic.setValue(pendingAck.getBytes(StandardCharsets.UTF_8));
                bluetoothGatt.writeCharacteristic(ackCharacteristic);
            } catch (JSONException error) {
                fail("账单数据格式错误");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(SyncProtocol.CONFIG_UUID.equals(characteristic.getUuid())
                        ? "发送账户与分类配置失败：" + status : "确认账单失败：" + status);
                return;
            }
            if (SyncProtocol.CONFIG_UUID.equals(characteristic.getUuid())) {
                if (configOffset < configPayload.length) writeNextConfigChunk(bluetoothGatt);
                else {
                    callback.onState("配置已同步，正在读取账单…");
                    bluetoothGatt.readCharacteristic(dataCharacteristic);
                }
                return;
            }
            pendingAck = null;
            bluetoothGatt.readCharacteristic(dataCharacteristic);
        }
    };

    private void beginConfigTransfer(BluetoothGatt bluetoothGatt) {
        if (configPayload.length == 0) {
            bluetoothGatt.readCharacteristic(dataCharacteristic);
            return;
        }
        int dataSize = configChunkDataSize();
        configOffset = 0;
        configChunkIndex = 0;
        configChunkCount = (configPayload.length + dataSize - 1) / dataSize;
        callback.onState("正在同步账户与分类配置…");
        writeNextConfigChunk(bluetoothGatt);
    }

    private void writeNextConfigChunk(BluetoothGatt bluetoothGatt) {
        int dataSize = configChunkDataSize();
        int end = Math.min(configPayload.length, configOffset + dataSize);
        byte[] chunk = new byte[10 + end - configOffset];
        chunk[0] = 0x43;
        chunk[1] = 1;
        putShort(chunk, 2, configChunkIndex);
        putShort(chunk, 4, configChunkCount);
        int length = configPayload.length;
        chunk[6] = (byte) (length >>> 24);
        chunk[7] = (byte) (length >>> 16);
        chunk[8] = (byte) (length >>> 8);
        chunk[9] = (byte) length;
        System.arraycopy(configPayload, configOffset, chunk, 10, end - configOffset);
        configCharacteristic.setValue(chunk);
        configOffset = end;
        configChunkIndex++;
        if (!bluetoothGatt.writeCharacteristic(configCharacteristic)) fail("无法发送账户与分类配置");
    }

    private int configChunkDataSize() {
        return Math.max(10, Math.min(480, negotiatedMtu - 13));
    }

    private static void putShort(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 8);
        value[offset + 1] = (byte) number;
    }

    private void fail(String message) {
        if (stopped) return;
        callback.onError(message);
        stop();
    }
}
