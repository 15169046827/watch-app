package com.canace.x3voiceledger.phone;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class WatchScreenshotMonitor {
    interface Callback {
        void onRecognized(long amountCents, String account);
        void onMessage(String message);
    }

    private static final String PREFS = "watch_screenshot_monitor";
    private static final String LAST_ID = "last_media_id";
    private static final String WATCH_PATH = "Pictures/com.heytap.health/screenshots/";
    private static final int MAX_SCAN_COUNT = 50;
    private final Context context;
    private final PhoneLedgerStore store;
    private final LedgerProfileStore profileStore;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;

    WatchScreenshotMonitor(Context context, PhoneLedgerStore store,
                           LedgerProfileStore profileStore, Callback callback) {
        this.context = context;
        this.store = store;
        this.profileStore = profileStore;
        this.callback = callback;
    }

    void start() {
        if (!hasImagePermission(context)) return;
        if (observer == null) {
            observer = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    handler.removeCallbacksAndMessages(null);
                    handler.postDelayed(WatchScreenshotMonitor.this::scanLatest, 1000L);
                }
            };
            context.getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        }
        scanLatest();
    }

    void close() {
        handler.removeCallbacksAndMessages(null);
        if (observer != null) context.getContentResolver().unregisterContentObserver(observer);
        observer = null;
    }

    static boolean hasImagePermission(Context context) {
        String permission = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES" : Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static String requiredPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES" : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static void rescanLatest(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_ID, 0L).apply();
        PhoneSyncService.start(context);
    }

    private void scanLatest() {
        ContentResolver resolver = context.getContentResolver();
        String[] projection = Build.VERSION.SDK_INT >= 29
                ? new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH}
                : new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA};
        String selection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Images.Media.RELATIVE_PATH + " = ?"
                : MediaStore.Images.Media.DATA + " LIKE ?";
        String[] args = Build.VERSION.SDK_INT >= 29
                ? new String[]{WATCH_PATH} : new String[]{"%/Pictures/com.heytap.health/screenshots/%"};
        try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                selection, args, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return;
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long previousId = preferences.getLong(LAST_ID, 0L);
            long newestId = previousId;
            List<Screenshot> screenshots = new ArrayList<>();
            do {
                long id = cursor.getLong(0);
                if (id <= previousId) break;
                newestId = Math.max(newestId, id);
                long taken = cursor.getLong(1);
                if (taken <= 0) taken = cursor.getLong(2) * 1000L;
                screenshots.add(new Screenshot(id, taken));
            } while (screenshots.size() < MAX_SCAN_COUNT && cursor.moveToNext());
            if (screenshots.isEmpty()) return;
            preferences.edit().putLong(LAST_ID, newestId).apply();
            boolean recognized = false;
            for (Screenshot screenshot : screenshots) {
                Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        Long.toString(screenshot.id));
                recognized |= recognize(uri, screenshot.id, screenshot.transactionTime);
            }
            if (!recognized) callback.onMessage("发现手表截图，但未发现可确认的付款金额");
        } catch (RuntimeException error) {
            callback.onMessage("无法读取手表截图，请检查照片权限");
        }
    }

    private boolean recognize(Uri uri, long mediaId, long transactionTime) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            long amountCents = PaymentScreenshotRecognizer.recognizeAlipayAmount(bitmap);
            if (amountCents <= 0) return false;
            saveResult(mediaId, transactionTime, amountCents);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void saveResult(long mediaId, long transactionTime, long amountCents) {
        String account = profileStore.accountForChannel("支付宝");
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", "watch-screenshot:" + mediaId);
            payload.put("updatedAt", transactionTime);
            payload.put("transactionTime", transactionTime);
            payload.put("amountCents", amountCents);
            payload.put("note", "支付宝手表付款（待补充内容）");
            payload.put("category", "其他");
            payload.put("categoryParent", "其他");
            payload.put("categoryChild", "");
            payload.put("account", account);
            payload.put("bookName", profileStore.bookName());
            payload.put("tags", profileStore.tags());
            payload.put("locationLabel", "");
            payload.put("source", "watch_screenshot");
            store.upsert(payload);
            callback.onRecognized(amountCents, account);
        } catch (JSONException ignored) {
            callback.onMessage("无法保存手表付款草稿");
        }
    }

    private static final class Screenshot {
        final long id;
        final long transactionTime;

        Screenshot(long id, long transactionTime) {
            this.id = id;
            this.transactionTime = transactionTime;
        }
    }
}
