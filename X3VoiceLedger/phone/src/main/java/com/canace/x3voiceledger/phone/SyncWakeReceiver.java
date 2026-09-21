package com.canace.x3voiceledger.phone;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SyncWakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                        && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                                == BluetoothAdapter.STATE_ON)) {
            PhoneSyncService.start(context);
        }
    }
}
