package com.example.x3accessibilityprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Runs an isolated, time-bounded BLE capability test inside the shared X3 probe app. */
public final class BleProbeService extends Service {
    public static final String PREFS = "ble_probe";
    public static final String KEY_RESULTS = "results";

    private static final String TAG = "X3BleProbe";
    private static final String CHANNEL = "ble_probe";
    private static final UUID SERVICE_UUID =
            UUID.fromString("7a2f1000-5d2b-4c93-9b65-6f8c2cf03001");
    private static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("7a2f1001-5d2b-4c93-9b65-6f8c2cf03001");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final Set<String> scanAddresses = new HashSet<>();
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer gattServer;
    private int scanResults;
    private boolean adv1Ok;
    private boolean adv2Ok;
    private boolean gattAdded;

    private final AdvertiseCallback advertise1Callback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            adv1Ok = true;
            line("advertise#1=SUCCESS mode=" + settingsInEffect.getMode()
                    + " tx=" + settingsInEffect.getTxPowerLevel());
        }

        @Override
        public void onStartFailure(int errorCode) {
            line("advertise#1=FAIL error=" + errorCode
                    + " (" + advertiseError(errorCode) + ")");
        }
    };

    private final AdvertiseCallback advertise2Callback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            adv2Ok = true;
            line("advertise#2=SUCCESS (concurrent)");
        }

        @Override
        public void onStartFailure(int errorCode) {
            line("advertise#2=FAIL error=" + errorCode
                    + " (" + advertiseError(errorCode) + ")");
        }
    };

    private final BluetoothGattServerCallback gattCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onServiceAdded(int status, BluetoothGattService service) {
                    gattAdded = status == BluetoothGatt.GATT_SUCCESS;
                    line("gatt.addService=" + (gattAdded ? "SUCCESS" : "FAIL")
                            + " status=" + status);
                }
            };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            scanResults++;
            if (result.getDevice() != null && result.getDevice().getAddress() != null) {
                scanAddresses.add(result.getDevice().getAddress());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            line("scan=FAIL error=" + errorCode + " (" + scanError(errorCode) + ")");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "BLE probe", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(31, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_probe)
                .setContentTitle("X3 BLE Probe")
                .setContentText("Running BLE capability probe")
                .setOngoing(true)
                .build());
        runProbe();
        return START_NOT_STICKY;
    }

    private void runProbe() {
        cleanupBle();
        handler.removeCallbacksAndMessages(null);
        report.setLength(0);
        scanAddresses.clear();
        scanResults = 0;
        adv1Ok = false;
        adv2Ok = false;
        gattAdded = false;
        line("probe=START api=" + android.os.Build.VERSION.SDK_INT);

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            line("adapter=UNAVAILABLE");
            finishProbe();
            return;
        }

        reportAdapterCapabilities(adapter);
        if (!adapter.isEnabled()) {
            finishProbe();
            return;
        }

        startAdvertising(adapter);
        startGattServer(manager);
        startScanner(adapter);
        handler.postDelayed(this::recordBackgroundCheckpoint, 15_000L);
        handler.postDelayed(this::finishProbe, 25_000L);
    }

    private void reportAdapterCapabilities(BluetoothAdapter adapter) {
        line("adapter.enabled=" + adapter.isEnabled());
        line("feature.multipleAdvertisement="
                + adapter.isMultipleAdvertisementSupported());
        line("feature.offloadedFiltering=" + adapter.isOffloadedFilteringSupported());
        line("feature.offloadedBatching=" + adapter.isOffloadedScanBatchingSupported());
        line("feature.maxAdvertisingDataLength="
                + adapter.getLeMaximumAdvertisingDataLength());
    }

    private void startAdvertising(BluetoothAdapter adapter) {
        advertiser = adapter.getBluetoothLeAdvertiser();
        line("advertiser.api=" + (advertiser == null ? "UNAVAILABLE" : "AVAILABLE"));
        if (advertiser != null) {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            advertiser.startAdvertising(
                    settings, advertisingData(0x07D1, "X1"), advertise1Callback);
            advertiser.startAdvertising(
                    settings, advertisingData(0x07D2, "X2"), advertise2Callback);
        }
    }

    private void startGattServer(BluetoothManager manager) {
        try {
            gattServer = manager.openGattServer(this, gattCallback);
            line("gatt.open=" + (gattServer == null ? "UNAVAILABLE" : "SUCCESS"));
            if (gattServer != null) {
                BluetoothGattService service = new BluetoothGattService(
                        SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                BluetoothGattCharacteristic characteristic =
                        new BluetoothGattCharacteristic(
                                CHARACTERISTIC_UUID,
                                BluetoothGattCharacteristic.PROPERTY_READ,
                                BluetoothGattCharacteristic.PERMISSION_READ);
                characteristic.setValue(
                        "x3-ble-probe".getBytes(StandardCharsets.UTF_8));
                service.addCharacteristic(characteristic);
                line("gatt.addService.accepted=" + gattServer.addService(service));
            }
        } catch (SecurityException error) {
            line("gatt=SECURITY_EXCEPTION " + error.getMessage());
        }
    }

    private void startScanner(BluetoothAdapter adapter) {
        scanner = adapter.getBluetoothLeScanner();
        line("scanner.api=" + (scanner == null ? "UNAVAILABLE" : "AVAILABLE"));
        if (scanner != null) {
            try {
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                scanner.startScan(null, settings, scanCallback);
                line("scan.start=ACCEPTED");
            } catch (SecurityException error) {
                line("scan=SECURITY_EXCEPTION " + error.getMessage());
            }
        }
    }

    private void recordBackgroundCheckpoint() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
                line("scan.stop=SUCCESS results=" + scanResults
                        + " uniqueDevices=" + scanAddresses.size());
            } catch (RuntimeException error) {
                line("scan.stop=FAIL " + error.getClass().getSimpleName());
            }
        }
        line("background.checkpoint=BLE_ALIVE adv1=" + adv1Ok
                + " adv2=" + adv2Ok + " gatt=" + gattAdded);
    }

    private AdvertiseData advertisingData(int manufacturerId, String marker) {
        return new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(
                        manufacturerId, marker.getBytes(StandardCharsets.US_ASCII))
                .build();
    }

    private void finishProbe() {
        line("probe=FINISH adv1=" + adv1Ok + " adv2=" + adv2Ok
                + " gattService=" + gattAdded + " scanResults=" + scanResults);
        cleanupBle();
        stopForeground(true);
        stopSelf();
    }

    private void cleanupBle() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup for a diagnostic probe.
            }
            scanner = null;
        }
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertise1Callback);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup for a diagnostic probe.
            }
            try {
                advertiser.stopAdvertising(advertise2Callback);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup for a diagnostic probe.
            }
            advertiser = null;
        }
        BluetoothGattServer server = gattServer;
        gattServer = null;
        if (server != null) {
            try {
                server.clearServices();
            } catch (RuntimeException error) {
                line("gatt.clear=FAIL " + error.getClass().getSimpleName());
            } finally {
                try {
                    server.close();
                } catch (RuntimeException error) {
                    line("gatt.close=FAIL " + error.getClass().getSimpleName());
                }
            }
        }
    }

    private void line(String value) {
        Log.i(TAG, value);
        if (report.length() > 0) {
            report.append('\n');
        }
        report.append(value);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RESULTS, report.toString())
                .apply();
    }

    private static String advertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "DATA_TOO_LARGE";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "TOO_MANY_ADVERTISERS";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            default:
                return "UNKNOWN";
        }
    }

    private static String scanError(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "REGISTRATION_FAILED";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        cleanupBle();
        super.onDestroy();
    }
}
