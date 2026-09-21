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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

final class WatchScreenshotMonitor {
    interface Callback {
        void onRecognized(long amountCents, String account);

        void onMessage(String message);
    }

    private static final String PREFS = "watch_screenshot_monitor";
    private static final String LAST_ID = "last_media_id";
    private static final String RETRY_ID = "retry_media_id";
    private static final String RETRY_ATTEMPTS = "retry_attempts";
    private static final String WATCH_PATH = "Pictures/com.heytap.health/screenshots/";
    private static final int MAX_SCAN_COUNT = 50;
    private final Context context;
    private final PhoneLedgerStore store;
    private final LedgerProfileStore profileStore;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;

    WatchScreenshotMonitor(
            Context context,
            PhoneLedgerStore store,
            LedgerProfileStore profileStore,
            Callback callback) {
        this.context = context;
        this.store = store;
        this.profileStore = profileStore;
        this.callback = callback;
    }

    void start() {
        if (!hasImagePermission(context)) {
            return;
        }
        if (observer == null) {
            observer =
                    new ContentObserver(handler) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            handler.removeCallbacksAndMessages(null);
                            handler.postDelayed(WatchScreenshotMonitor.this::scanLatest, 1000L);
                        }
                    };
            context.getContentResolver()
                    .registerContentObserver(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        }
        scanLatest();
    }

    void close() {
        handler.removeCallbacksAndMessages(null);
        if (observer != null) {
            context.getContentResolver().unregisterContentObserver(observer);
        }
        observer = null;
    }

    static boolean hasImagePermission(Context context) {
        String permission =
                Build.VERSION.SDK_INT >= 33
                        ? "android.permission.READ_MEDIA_IMAGES"
                        : Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static String requiredPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static void rescanLatest(Context context) {
        clearState(context);
        PhoneSyncService.start(context);
    }

    static void clearState(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private void scanLatest() {
        try (Cursor cursor = queryLatest()) {
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            processLatest(cursor);
        } catch (RuntimeException error) {
            callback.onMessage("无法读取手表截图，请检查照片权限");
        }
    }

    private Cursor queryLatest() {
        ContentResolver resolver = context.getContentResolver();
        long previousId =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_ID, 0L);
        return resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                screenshotProjection(),
                screenshotSelection(),
                screenshotSelectionArgs(previousId),
                MediaStore.Images.Media._ID + " ASC");
    }

    private static String[] screenshotProjection() {
        if (Build.VERSION.SDK_INT >= 29) {
            return new String[] {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            };
        }
        return new String[] {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA
        };
    }

    private static String screenshotSelection() {
        String path =
                Build.VERSION.SDK_INT >= 29
                        ? MediaStore.Images.Media.RELATIVE_PATH + " = ?"
                        : MediaStore.Images.Media.DATA + " LIKE ?";
        return path + " AND " + MediaStore.Images.Media._ID + " > ?";
    }

    private static String[] screenshotSelectionArgs(long previousId) {
        String path =
                Build.VERSION.SDK_INT >= 29
                        ? WATCH_PATH
                        : "%/Pictures/com.heytap.health/screenshots/%";
        return new String[] {path, Long.toString(previousId)};
    }

    private void processLatest(Cursor cursor) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ScreenshotBatch batch = readBatch(cursor, preferences.getLong(LAST_ID, 0L));
        if (batch.screenshots.isEmpty()) {
            return;
        }
        boolean recognized = false;
        for (Screenshot screenshot : batch.screenshots) {
            RecognitionResult result = recognize(screenshot);
            if (result == RecognitionResult.RETRY) {
                handleRetry(preferences, screenshot.id);
                return;
            }
            recognized |= result == RecognitionResult.RECOGNIZED;
            commitProcessed(preferences, screenshot.id);
        }
        if (!recognized) {
            callback.onMessage("发现手表截图，但未发现可确认的付款金额");
        }
        if (batch.screenshots.size() == MAX_SCAN_COUNT) {
            handler.postDelayed(this::scanLatest, 250L);
        }
    }

    private void handleRetry(SharedPreferences preferences, long mediaId) {
        long previousRetryId = preferences.getLong(RETRY_ID, 0L);
        int previousAttempts = preferences.getInt(RETRY_ATTEMPTS, 0);
        int attempts =
                ScreenshotRetryPolicy.nextAttempt(mediaId, previousRetryId, previousAttempts);
        if (ScreenshotRetryPolicy.shouldRetry(attempts)) {
            preferences
                    .edit()
                    .putLong(RETRY_ID, mediaId)
                    .putInt(RETRY_ATTEMPTS, attempts)
                    .commit();
            callback.onMessage("手表截图暂时不可读，将自动重试");
            handler.postDelayed(this::scanLatest, 5000L);
            return;
        }
        commitProcessed(preferences, mediaId);
        callback.onMessage("一张手表截图连续读取失败，已跳过并继续扫描");
        handler.postDelayed(this::scanLatest, 250L);
    }

    private static void commitProcessed(SharedPreferences preferences, long mediaId) {
        preferences
                .edit()
                .putLong(LAST_ID, mediaId)
                .remove(RETRY_ID)
                .remove(RETRY_ATTEMPTS)
                .commit();
    }

    private static ScreenshotBatch readBatch(Cursor cursor, long previousId) {
        List<Screenshot> screenshots = new ArrayList<>();
        do {
            long id = cursor.getLong(0);
            if (id > previousId) {
                screenshots.add(new Screenshot(id, transactionTime(cursor)));
            }
        } while (screenshots.size() < MAX_SCAN_COUNT && cursor.moveToNext());
        return new ScreenshotBatch(screenshots);
    }

    private static long transactionTime(Cursor cursor) {
        long taken = cursor.getLong(1);
        return taken > 0 ? taken : cursor.getLong(2) * 1000L;
    }

    private RecognitionResult recognize(Screenshot screenshot) {
        Uri uri =
                Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        Long.toString(screenshot.id));
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) {
                return RecognitionResult.RETRY;
            }
            long amountCents = PaymentScreenshotRecognizer.recognizeAlipayAmount(bitmap);
            if (amountCents <= 0) {
                return RecognitionResult.IGNORED;
            }
            return saveResult(screenshot.id, screenshot.transactionTime, amountCents)
                    ? RecognitionResult.RECOGNIZED
                    : RecognitionResult.RETRY;
        } catch (Exception error) {
            return RecognitionResult.RETRY;
        }
    }

    private boolean saveResult(long mediaId, long transactionTime, long amountCents) {
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
            return true;
        } catch (JSONException | RuntimeException error) {
            callback.onMessage("无法保存手表付款草稿，将自动重试");
            return false;
        }
    }

    private enum RecognitionResult {
        RECOGNIZED,
        IGNORED,
        RETRY
    }

    private static final class ScreenshotBatch {
        final List<Screenshot> screenshots;

        ScreenshotBatch(List<Screenshot> screenshots) {
            this.screenshots = screenshots;
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
