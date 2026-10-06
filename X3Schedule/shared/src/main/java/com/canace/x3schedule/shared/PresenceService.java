package com.canace.x3schedule.shared;

import android.annotation.TargetApi;
import android.companion.CompanionDeviceService;
import java.util.HashSet;
import java.util.Set;

/**
 * Declared only in the phone manifest. No foreground notification and no private content in callbacks.
 */
@TargetApi(31)
public final class PresenceService extends CompanionDeviceService {

    private final Set<String> present = new HashSet<>();

    @Override
    public void onDeviceAppeared(String address) {
        try (Store store = new Store(this)) {
            if (!address.equals(store.meta("peer", "")) || store.meta("sync_paused", "false").equals("true")
                    || !PresenceBinding.isAssociated(this, address)) {
                return;
            }
            present.add(address);
            SyncRuntime.get(this).acquire("presence:" + address);
        }
    }

    @Override
    public void onDeviceDisappeared(String address) {
        present.remove(address);
        SyncRuntime.get(this).release("presence:" + address);
    }

    @Override
    public void onDestroy() {
        for (String address : present) {
            SyncRuntime.get(this).release("presence:" + address);
        }
        present.clear();
        super.onDestroy();
    }
}
