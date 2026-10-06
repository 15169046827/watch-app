package com.canace.x3schedule.shared;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.companion.DeviceNotAssociatedException;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * The system, rather than a silent app scan, asks the user to associate the selected bonded watch.
 */
@TargetApi(31)
public final class PresenceBinding {

    // Keep separate from the Activity's document requests (20 through 24).
    public static final int REQUEST = 30;

    private PresenceBinding() {
    }

    public static boolean isAssociated(android.content.Context context, String address) {
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        return !address.isEmpty() && manager != null && manager.getAssociations().contains(address);
    }

    public static void disassociate(android.content.Context context, String address) {
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        if (!address.isEmpty() && manager != null && manager.getAssociations().contains(address)) {
            try {
                // Stop observation while the association still exists, allowing the system to unbind.
                manager.stopObservingDevicePresence(address);
            } catch (DeviceNotAssociatedException error) {
                throw new IllegalArgumentException("设备关联已变化，请返回连接页后重试", error);
            }
            // This removes only this app's association; the system Bluetooth bond is retained.
            manager.disassociate(address);
        }
        SyncRuntime.get(context).release("presence:" + address);
    }

    public static void associate(Activity activity, String address) {
        if (address.isEmpty()) {
            throw new IllegalArgumentException("请先选择已配对手表");
        }
        CompanionDeviceManager manager = activity.getSystemService(CompanionDeviceManager.class);
        if (manager == null) {
            throw new IllegalArgumentException("当前系统不支持伴随设备");
        }
        if (manager.getAssociations().contains(address)) {
            observe(activity, address);
            return;
        }
        AssociationRequest request = new AssociationRequest.Builder().addDeviceFilter(new BluetoothDeviceFilter
                .Builder().setAddress(address).build()).setSingleDevice(true).build();
        manager.associate(request, new CompanionDeviceManager.Callback() {

            @Override
            public void onDeviceFound(IntentSender chooser) {
                try {
                    activity.startIntentSenderForResult(chooser, REQUEST, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Toast.makeText(activity, "系统关联窗口无法打开，请重试", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                Toast.makeText(activity, "未完成设备关联，可稍后重试", Toast.LENGTH_LONG).show();
            }
        }, new Handler(Looper.getMainLooper()));
    }

    public static boolean observe(android.content.Context context, String address) {
        try (Store store = new Store(context)) {
            if (store.meta("sync_paused", "false").equals("true")) {
                return false;
            }
        }
        return observeIfPermitted(context, address);
    }

    private static boolean observeIfPermitted(android.content.Context context, String address) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager
                .PERMISSION_GRANTED) {
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        if (manager == null || !manager.getAssociations().contains(address)) {
            return false;
        }
        try {
            manager.startObservingDevicePresence(address);
            return true;
        } catch (DeviceNotAssociatedException | SecurityException e) {
            return false;
        }
    }
}
