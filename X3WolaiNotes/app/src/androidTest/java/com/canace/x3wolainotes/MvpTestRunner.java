package com.canace.x3wolainotes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;
import java.util.UUID;

public final class MvpTestRunner extends Instrumentation {
    private static final String TEST_TEXT = "X3_MVP_TEST_20260817";
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("offline-ui".equals(arguments == null ? "" : arguments.getString("mode", ""))) {
                runOfflineUi();
                result.putString("stream", "V7_OFFLINE_UI_OK\n");
            } else {
                verifyRecentPagesStore();
                runReadWriteRoundTrip();
                verifyUnknownWriteOutcome();
                verifyErrorMessages();
                result.putString("stream",
                        "V7_RECENTS_READ_WRITE_RECONCILE_FAVORITES_DATABASE_ERRORS_UNKNOWN_WRITE_OK\n");
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "MVP_READ_WRITE_FAILED: " + safeMessage(error) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runOfflineUi() throws Exception {
        Context context = getTargetContext();
        String token = new SecretStore(context).loadToken();
        require(token != null && token.length() >= 20, "Provisioned token unavailable");
        Intent intent = new Intent(context, ReaderActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        WolaiClient.setForceOfflineForTest(true);
        Activity activity = startActivitySync(intent);
        try {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                waitForIdleSync();
                final String[] visibleText = new String[1];
                runOnMainSync(() -> visibleText[0] =
                        collectText(activity.getWindow().getDecorView()));
                if (visibleText[0].contains("网络不可用 · 请联网后重试")) return;
                Thread.sleep(1000L);
            }
            throw new AssertionError("Offline reader message not observed");
        } finally {
            WolaiClient.resetTestFaults();
            runOnMainSync(activity::finish);
        }
    }

    private void verifyUnknownWriteOutcome() throws Exception {
        Context context = getTargetContext();
        String token = new SecretStore(context).loadToken();
        require(token != null && token.length() >= 20, "Provisioned token unavailable");
        require(AppConfig.hasTargetPage(), "Target page unavailable");
        WolaiClient client = new WolaiClient(token);
        WolaiClient.setForceUnknownWriteForTest(true);
        try {
            client.createTextBlock(AppConfig.TARGET_PAGE_ID, "X3_FAULT_INJECTION_NO_SEND");
            throw new AssertionError("Unknown write fault was not raised");
        } catch (WolaiClient.ApiException error) {
            require(error.statusCode() == 0, "Unknown write fault had HTTP status");
            require(error.isOutcomeUnknown(), "Unknown write fault was not marked uncertain");
        } finally {
            WolaiClient.resetTestFaults();
        }
    }

    private void verifyRecentPagesStore() {
        Context context = getTargetContext();
        String databaseName = "x3_wolai_notes_recent_test.db";
        context.deleteDatabase(databaseName);
        SQLiteDatabase legacy = context.openOrCreateDatabase(databaseName,
                Context.MODE_PRIVATE, null);
        legacy.execSQL("CREATE TABLE local_notes (" +
                "id TEXT PRIMARY KEY," +
                "text TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "state INTEGER NOT NULL," +
                "error TEXT NOT NULL DEFAULT ''," +
                "last_attempt_at INTEGER NOT NULL DEFAULT 0)");
        legacy.execSQL("INSERT INTO local_notes " +
                "(id, text, kind, created_at, state, error, last_attempt_at) " +
                "VALUES ('migration-check', 'test', 0, 1, 2, '', 0)");
        legacy.setVersion(3);
        legacy.close();

        NoteStore store = new NoteStore(context, databaseName);
        try {
            require(store.recentLocal(5).size() == 1,
                    "SQLite v3 notes were not preserved");
            for (int index = 0; index < 12; index++) {
                store.recordRecentPage("page-" + index, "页面 " + index, 1000L + index);
            }
            List<RecentPage> recent = store.recentPages(20);
            require(recent.size() == NoteStore.MAX_RECENT_PAGES,
                    "Recent page limit failed");
            require("page-11".equals(recent.get(0).id), "Recent page ordering failed");
            require("page-2".equals(recent.get(9).id), "Recent page trimming failed");

            store.recordRecentPage("page-5", "更新标题", 2000L);
            recent = store.recentPages(10);
            require("page-5".equals(recent.get(0).id), "Recent page refresh failed");
            require("更新标题".equals(recent.get(0).title), "Recent title update failed");
            store.clearRecentPages();
            require(store.recentPages(10).isEmpty(), "Recent page clear failed");
        } finally {
            store.close();
            context.deleteDatabase(databaseName);
        }
    }

    private static String collectText(View view) {
        StringBuilder result = new StringBuilder();
        appendText(view, result);
        return result.toString();
    }

    private static void appendText(View view, StringBuilder result) {
        if (view instanceof TextView) {
            result.append(((TextView) view).getText()).append('\n');
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            appendText(group.getChildAt(index), result);
        }
    }

    private static void verifyErrorMessages() {
        require(UserErrorMessages.syncStatus(401).startsWith("授权已失效"),
                "401 message missing");
        require(UserErrorMessages.syncStatus(403).contains("权限"), "403 message missing");
        require(UserErrorMessages.syncStatus(404).contains("不可用"), "404 message missing");
        require(UserErrorMessages.syncStatus(429).contains("请求过快"), "429 message missing");
        require(UserErrorMessages.readerStatus(503).contains("服务暂时不可用"),
                "503 message missing");
    }

    private void runReadWriteRoundTrip() throws Exception {
        Context context = getTargetContext();
        SecretStore secrets = new SecretStore(context);
        String token = secrets.loadToken();
        require(token != null && token.length() >= 20, "Provisioned token unavailable");
        require(AppConfig.hasTargetPage(), "Target page unavailable");
        require(AppConfig.hasFavoritesPage(), "Favorites page unavailable");

        NoteStore store = new NoteStore(context);
        try {
            WolaiClient client = new WolaiClient(token);
            List<WolaiClient.RemoteBlock> before = client.fetchChildren(AppConfig.TARGET_PAGE_ID);
            boolean alreadyPresent = contains(before, TEST_TEXT);

            NoteItem local = new NoteItem(UUID.randomUUID().toString(), TEST_TEXT,
                    NoteItem.KIND_NOTE, System.currentTimeMillis(), NoteItem.STATE_PENDING, "");
            store.insertPending(local);
            require(containsId(store.pending(100), local.id), "Local queue insert failed");
            long attemptedAt = System.currentTimeMillis();
            store.markAttempting(local.id, attemptedAt);
            NoteItem attempting = findById(store.pending(100), local.id);
            require(attempting != null && attempting.state == NoteItem.STATE_UNCERTAIN,
                    "Attempt was not persisted as uncertain");
            require(attempting.lastAttemptAt == attemptedAt, "Attempt timestamp was not persisted");

            if (!alreadyPresent) {
                client.createTextBlock(AppConfig.TARGET_PAGE_ID,
                        NoteFormatter.forWolai(local.kind, local.text));
            }
            store.markSynced(local.id);
            require(!containsId(store.pending(100), local.id), "Local queue completion failed");

            List<WolaiClient.RemoteBlock> after = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                after = client.fetchChildren(AppConfig.TARGET_PAGE_ID);
                if (contains(after, TEST_TEXT)) break;
                Thread.sleep(1000L);
            }
            require(after != null && contains(after, TEST_TEXT), "Remote round-trip failed");

            List<WolaiClient.RemoteBlock> favorites;
            try {
                favorites = client.fetchChildren(AppConfig.FAVORITES_PAGE_ID);
            } catch (WolaiClient.ApiException error) {
                throw new AssertionError("Favorites directory unreadable: " + safeMessage(error));
            }
            WolaiClient.RemoteBlock firstPage = firstPageLink(favorites);
            require(firstPage != null, "Favorites page has no page references");
            try {
                client.fetchPage(firstPage.targetPageId);
            } catch (WolaiClient.ApiException error) {
                throw new AssertionError("Favorite target unreadable: " + safeMessage(error));
            }
            verifyDatabaseReading(client, favorites);
        } finally {
            store.close();
        }
    }

    private static void verifyDatabaseReading(WolaiClient client,
                                              List<WolaiClient.RemoteBlock> favorites)
            throws Exception {
        int inspectedPages = 0;
        for (WolaiClient.RemoteBlock favorite : favorites) {
            if (!favorite.isPageLink() || inspectedPages++ >= 20) continue;
            List<WolaiClient.RemoteBlock> blocks = client.fetchPage(favorite.targetPageId);
            for (WolaiClient.RemoteBlock block : blocks) {
                if (!"database".equals(block.type)) continue;
                WolaiClient.DatabaseTable table = client.fetchDatabase(block.id);
                require(!table.columnOrder.isEmpty(), "Database has no columns");
                require(!table.rows.isEmpty(), "Database has no rows");
                for (WolaiClient.DatabaseRow row : table.rows) {
                    require(row.cells.size() == table.columnOrder.size(),
                            "Database row/column mismatch");
                }
                return;
            }
        }
        throw new AssertionError("No database found in favorite targets");
    }

    private static WolaiClient.RemoteBlock firstPageLink(List<WolaiClient.RemoteBlock> blocks) {
        for (WolaiClient.RemoteBlock block : blocks) {
            if (block.isPageLink()) return block;
        }
        return null;
    }

    private static boolean containsId(List<NoteItem> items, String expectedId) {
        return findById(items, expectedId) != null;
    }

    private static NoteItem findById(List<NoteItem> items, String expectedId) {
        for (NoteItem item : items) {
            if (expectedId.equals(item.id)) return item;
        }
        return null;
    }

    private static boolean contains(List<WolaiClient.RemoteBlock> blocks, String expected) {
        for (WolaiClient.RemoteBlock block : blocks) {
            if (expected.equals(block.text)) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        message = message.trim();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}
