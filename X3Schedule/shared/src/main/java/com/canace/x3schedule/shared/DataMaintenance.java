package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Serializes destructive local actions after all pending application writers and BLE callbacks. */
final class DataMaintenance {
    enum Kind { BUSINESS, CACHE, CONNECTION }
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "schedule-maintenance"));

    private DataMaintenance() {
    }

    static boolean active() {
        return ACTIVE.get();
    }

    static void run(Activity activity, Kind kind) {
        if (!ACTIVE.compareAndSet(false, true)) {
            return;
        }
        Context context = activity.getApplicationContext();
        AlertDialog waiting = new AlertDialog.Builder(activity).setMessage("正在等待保存和同步结束，再处理本机数据…")
                .setCancelable(false).create();
        try {
            waiting.show();
        } catch (RuntimeException error) {
            ACTIVE.set(false);
            throw error;
        }
        SyncRuntime.get(context).drainForMaintenance(() -> PlanPreviewScreen.drainForMaintenance(
                () -> BackupScreen.drainForMaintenance(() -> ReminderWork.drainForMaintenance(
                        () -> WORKER.execute(() -> complete(activity, context, kind, waiting))))));
    }

    private static void complete(Activity activity, Context context, Kind kind, AlertDialog waiting) {
        String message;
        try {
            message = perform(context, kind);
        } catch (IOException | RuntimeException error) {
            message = "处理未完成，请重新打开隐私与本机数据检查；不会删除其他设备或外部文件。";
        }
        String result = message;
        new Handler(Looper.getMainLooper()).post(() -> {
            ACTIVE.set(false);
            SyncRuntime.get(context).reconnect();
            if (activity instanceof ScheduleActivity) {
                ((ScheduleActivity) activity).invalidateCalendar();
            }
            if (!activity.isDestroyed()) {
                waiting.dismiss();
                Toast.makeText(activity, result, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String perform(Context context, Kind kind) throws IOException {
        try (Store store = new Store(context)) {
            switch (kind) {
                case BUSINESS:
                    LocalDataStore.clearBusiness(store);
                    if (store.phone && Build.VERSION.SDK_INT >= 31) {
                        updateAssociations(context, false);
                    }
                    context.stopService(new Intent(context, WatchConnectionService.class));
                    context.getSystemService(NotificationManager.class).cancel(100);
                    ReminderReceiver.schedule(context, false);
                    return "本机日程与记录已清除，同步已暂停。设置及设备选择保留。";
                case CACHE:
                    LocalDataStore.clearTransferCache(store);
                    clearCache(context);
                    return "缓存已清除，日程和记录保留；需要同步的内容会重新传输。";
                case CONNECTION:
                    store.putMeta("sync_paused", "true");
                    store.putMeta("background_sync", "false");
                    context.stopService(new Intent(context, WatchConnectionService.class));
                    if (store.phone && Build.VERSION.SDK_INT >= 31) {
                        updateAssociations(context, true);
                    }
                    store.putMeta("peer", "");
                    return "本应用设备选择与后台关联已清除；系统蓝牙配对、权限和日程保留。";
                default:
                    throw new IllegalArgumentException("Unknown maintenance action");
            }
        }
    }

    @android.annotation.TargetApi(31)
    private static void updateAssociations(Context context, boolean remove) {
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        if (manager != null) {
            for (String address : new ArrayList<>(manager.getAssociations())) {
                // Runtime leases live on the main thread; the maintenance barrier already drained the link.
                try {
                    manager.stopObservingDevicePresence(address);
                    if (remove) {
                        manager.disassociate(address);
                    }
                } catch (android.companion.DeviceNotAssociatedException error) {
                    throw new IllegalArgumentException("设备关联已变化，请重试", error);
                }
            }
        }
    }

    private static void clearCache(Context context) throws IOException {
        Path root = context.getCacheDir().toPath();
        // Files.walk does not follow symbolic links; delete only descendants of this app's cache.
        try (Stream<Path> paths = Files.walk(root)) {
            Path[] descendants = paths.filter(path -> !path.equals(root)).sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new);
            for (Path path : descendants) {
                Files.deleteIfExists(path);
            }
        }
    }
}
