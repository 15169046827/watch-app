package com.canace.x3voiceledger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;

/** Test-only BLE peripheral that never opens the production ledger database. */
@SuppressLint("MissingPermission")
public final class SyntheticBlePeripheralRunner extends Instrumentation {
    private static final UUID SERVICE_UUID =
            UUID.fromString("7bc0f101-9d38-4f90-83a2-4f77f3f0a100");
    private static final UUID DATA_UUID =
            UUID.fromString("7bc0f102-9d38-4f90-83a2-4f77f3f0a100");
    private static final UUID ACK_UUID =
            UUID.fromString("7bc0f103-9d38-4f90-83a2-4f77f3f0a100");
    private static final UUID CONFIG_UUID =
            UUID.fromString("7bc0f104-9d38-4f90-83a2-4f77f3f0a100");
    private static final String SYNTHETIC_ID = "synthetic-ble-acceptance-v1";
    private static final long READY_TIMEOUT_SECONDS = 30L;
    private static final long ACCEPTANCE_TIMEOUT_SECONDS = 180L;

    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch complete = new CountDownLatch(1);
    private final AtomicInteger ledgerReads = new AtomicInteger();
    private final AtomicInteger emptyReads = new AtomicInteger();
    private final AtomicInteger ackAttempts = new AtomicInteger();
    private volatile Throwable asyncFailure;
    private volatile boolean acknowledged;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private byte[] ledgerPayload;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            runAcceptance();
            result.putString("stream", "WATCH_SYNTHETIC_BLE_OK\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString(
                    "stream",
                    "WATCH_SYNTHETIC_BLE_FAILED: "
                            + error.getClass().getSimpleName()
                            + ": "
                            + error.getMessage()
                            + "\n");
        } finally {
            stopBluetooth();
        }
        finish(resultCode, result);
    }

    private void runAcceptance() throws Exception {
        ledgerPayload = createSyntheticPayload();
        startGattServer();
        require(ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "BLE advertising timed out");
        throwAsyncFailure();
        require(
                complete.await(ACCEPTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Phone acceptance did not complete");
        throwAsyncFailure();
        require(ledgerReads.get() >= 2, "Synthetic ledger was not replayed after failed ACK");
        require(ackAttempts.get() == 2, "Expected exactly two ACK attempts");
        require(emptyReads.get() >= 1, "Phone did not verify the empty state after ACK");
    }

    private byte[] createSyntheticPayload() throws JSONException {
        long timestamp = 1_789_000_000_000L;
        LedgerEntry entry =
                new LedgerEntry(
                        SYNTHETIC_ID,
                        timestamp,
                        timestamp,
                        timestamp,
                        1234L,
                        "synthetic BLE acceptance",
                        "synthetic",
                        "audit",
                        "ble",
                        "test-only",
                        "acceptance",
                        "synthetic",
                        "synthetic BLE acceptance",
                        "synthetic_ble_acceptance",
                        null,
                        null,
                        null,
                        null,
                        "");
        return LedgerPayloadCodec.ledger(entry);
    }

    private void startGattServer() {
        Context context = getTargetContext();
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter != null && adapter.isEnabled(), "Bluetooth is not enabled");
        advertiser = adapter.getBluetoothLeAdvertiser();
        require(advertiser != null, "BLE advertising is unavailable");
        gattServer = manager.openGattServer(context, gattCallback);
        require(gattServer != null, "Unable to open test GATT server");

        BluetoothGattService service =
                new BluetoothGattService(
                        SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(
                new BluetoothGattCharacteristic(
                        DATA_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM));
        service.addCharacteristic(
                new BluetoothGattCharacteristic(
                        ACK_UUID,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM));
        service.addCharacteristic(
                new BluetoothGattCharacteristic(
                        CONFIG_UUID,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM));
        require(gattServer.addService(service), "Unable to add test GATT service");
    }

    private final BluetoothGattServerCallback gattCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onServiceAdded(int status, BluetoothGattService service) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failAsync("Test GATT service registration failed: " + status);
                        return;
                    }
                    startAdvertising();
                }

                @Override
                public void onConnectionStateChange(
                        BluetoothDevice device, int status, int newState) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
                            && acknowledged
                            && emptyReads.get() >= 1) {
                        complete.countDown();
                    }
                }

                @Override
                public void onCharacteristicReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattCharacteristic characteristic) {
                    handleRead(device, requestId, offset, characteristic);
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {
                    handleWrite(
                            device,
                            requestId,
                            characteristic,
                            preparedWrite,
                            responseNeeded,
                            offset,
                            value);
                }
            };

    private void startAdvertising() {
        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();
        AdvertiseData data =
                new AdvertiseData.Builder()
                        .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                        .setIncludeDeviceName(false)
                        .build();
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (RuntimeException error) {
            failAsync("Test BLE advertising failed", error);
        }
    }

    private final AdvertiseCallback advertiseCallback =
            new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    ready.countDown();
                }

                @Override
                public void onStartFailure(int errorCode) {
                    failAsync("Test BLE advertising failed: " + errorCode);
                }
            };

    private void handleRead(
            BluetoothDevice device,
            int requestId,
            int offset,
            BluetoothGattCharacteristic characteristic) {
        if (!DATA_UUID.equals(characteristic.getUuid())) {
            sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null);
            return;
        }
        byte[] value = acknowledged ? LedgerPayloadCodec.empty() : ledgerPayload;
        if (offset < 0 || offset > value.length) {
            sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            return;
        }
        if (offset == 0) {
            if (acknowledged) {
                emptyReads.incrementAndGet();
            } else {
                ledgerReads.incrementAndGet();
            }
        }
        sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                Arrays.copyOfRange(value, offset, value.length));
    }

    private void handleWrite(
            BluetoothDevice device,
            int requestId,
            BluetoothGattCharacteristic characteristic,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value) {
        int status = BluetoothGatt.GATT_FAILURE;
        if (ACK_UUID.equals(characteristic.getUuid()) && !preparedWrite && offset == 0) {
            String id = value == null ? "" : new String(value, StandardCharsets.UTF_8);
            if (SYNTHETIC_ID.equals(id)) {
                int attempt = ackAttempts.incrementAndGet();
                if (attempt == 1) {
                    gattServer.cancelConnection(device);
                    return;
                } else {
                    acknowledged = true;
                    status = BluetoothGatt.GATT_SUCCESS;
                }
            }
        }
        if (responseNeeded) {
            sendResponse(device, requestId, status, offset, null);
        }
    }

    private void sendResponse(
            BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        if (gattServer == null || !gattServer.sendResponse(device, requestId, status, offset, value)) {
            failAsync("Unable to send test GATT response");
        }
    }

    private void failAsync(String message) {
        failAsync(message, null);
    }

    private void failAsync(String message, Throwable cause) {
        asyncFailure = cause == null ? new AssertionError(message) : new AssertionError(message, cause);
        ready.countDown();
        complete.countDown();
    }

    private void throwAsyncFailure() throws Exception {
        Throwable failure = asyncFailure;
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new AssertionError(failure);
    }

    private void stopBluetooth() {
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (RuntimeException ignored) {
            }
            advertiser = null;
        }
        if (gattServer != null) {
            gattServer.clearServices();
            gattServer.close();
            gattServer = null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
