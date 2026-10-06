package com.canace.x3schedule.shared;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * A process owns one BLE link, with explicit lifecycle leases from UI, jobs and companion services.
 */
public final class SyncRuntime {

    private static SyncRuntime instance;

    private final Context context;

    private final Store store;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Set<String> owners = new HashSet<>();

    private final Set<Runnable> observers = new HashSet<>();

    private BleLink link;

    private final Runnable idleClose = () -> {
        if (owners.isEmpty() && link != null) {
            link.close();
            link = null;
            notifyChanged();
        }
    };

    private SyncRuntime(Context context) {
        this.context = context.getApplicationContext();
        store = new Store(this.context);
    }

    public static synchronized SyncRuntime get(Context context) {
        if (instance == null) {
            instance = new SyncRuntime(context);
        }
        return instance;
    }

    public void acquire(String owner) {
        handler.removeCallbacks(idleClose);
        owners.add(owner);
        if (DataMaintenance.active() || store.meta("sync_paused", "false").equals("true")) {
            return;
        }
        if (link == null) {
            link = new BleLink(context, store, this::notifyChanged);
            link.start();
        }
    }

    public void release(String owner) {
        owners.remove(owner);
        if (owners.isEmpty()) {
            handler.postDelayed(idleClose, 30000);
        }
    }

    public void observe(Runnable callback) {
        observers.add(callback);
    }

    public void unobserve(Runnable callback) {
        observers.remove(callback);
    }

    public String status() {
        if (store.meta("sync_paused", "false").equals("true")) {
            return "本机同步已暂停，可在连接与权限恢复";
        }
        return link == null ? "未连接；对方的新记录未知" : link.status;
    }

    public void reconnect() {
        if (link != null) {
            link.close();
            link = null;
        }
        if (!owners.isEmpty()) {
            acquire(owners.iterator().next());
        }
    }

    private void notifyChanged() {
        for (Runnable callback : new ArrayList<>(observers)) {
            callback.run();
        }
    }

    void drainForMaintenance(Runnable drained) {
        handler.removeCallbacks(idleClose);
        BleLink previous = link;
        link = null;
        if (previous == null) {
            drained.run();
        } else {
            previous.closeAndDrain(drained);
        }
    }
}
