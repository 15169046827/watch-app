package com.canace.x3wolainotes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
            } else if ("backup-synthetic".equals(
                    arguments == null ? "" : arguments.getString("mode", ""))) {
                verifyRecentPagesStore();
                verifyBackupRoundTrip();
                result.putString("stream", "V8_SYNTHETIC_BACKUP_MIGRATION_OK\n");
            } else if ("personal-token-ui".equals(
                    arguments == null ? "" : arguments.getString("mode", ""))) {
                verifyPersonalTokenUi();
                result.putString("stream", "V10_TOKEN_METADATA_ONLY_UI_OK\n");
            } else {
                verifyRecentPagesStore();
                verifyBackupRoundTrip();
                runReadWriteRoundTrip();
                verifyUnknownWriteOutcome();
                verifyErrorMessages();
                result.putString("stream",
                        "V8_BACKUP_RECENTS_READ_WRITE_RECONCILE_FAVORITES_DATABASE_ERRORS_UNKNOWN_WRITE_OK\n");
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "MVP_READ_WRITE_FAILED: " + safeMessage(error) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifyPersonalTokenUi() throws Exception {
        Context context = getTargetContext();
        Intent intent = new Intent(context, DataManagementActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = startActivitySync(intent);
        try {
            waitForIdleSync();
            Thread.sleep(2_000L);
            waitForIdleSync();
            final String[] visibleText = new String[1];
            final EditText[] tokenField = new EditText[1];
            final EditText[] targetField = new EditText[1];
            final EditText[] favoritesField = new EditText[1];
            runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                visibleText[0] = collectText(root);
                tokenField[0] = findEditTextByHint(root, "个人 Token（不会回显）");
                targetField[0] = findEditTextByHint(root, "同步页面 ID");
                favoritesField[0] = findEditTextByHint(root, "收藏页面 ID");
            });
            require(visibleText[0].contains("个人 Token 不是 OAuth"),
                    "Personal token warning missing");
            require(visibleText[0].contains("状态："),
                    "Personal token status missing");
            require(tokenField[0] == null,
                    "Persistent personal token input must not exist");
            require(targetField[0] == null && favoritesField[0] == null,
                    "Persistent page ID inputs must not exist");
            PageConfigStore pages = new PageConfigStore(context);
            if (pages.hasConfiguredPages()) {
                require(visibleText[0].contains("配置时间："),
                        "Page configuration time missing");
                require(visibleText[0].contains("页面 ID 不显示、不可修改"),
                        "Immutable page configuration status missing");
                require(visibleText[0].contains("删除页面配置"),
                        "Page configuration delete action missing");
                require(!visibleText[0].contains("配置 Wolai 页面"),
                        "Configured pages still offer direct editing");
            } else {
                require(visibleText[0].contains("配置 Wolai 页面"),
                        "Page configuration action missing");
                require(!visibleText[0].contains("删除页面配置"),
                        "Unconfigured pages offer delete action");
            }
            if (new SecretStore(context).hasStoredToken()) {
                require(visibleText[0].contains("导入时间："),
                        "Personal token import time missing");
                require(visibleText[0].contains("Token 不显示、不可修改"),
                        "Immutable token status missing");
                require(visibleText[0].contains("删除个人 Token"),
                        "Personal token delete action missing");
                require(!visibleText[0].contains("导入个人 Token"),
                        "Configured token still offers direct import");
            } else {
                require(visibleText[0].contains("导入个人 Token"),
                        "Personal token import action missing");
                require(!visibleText[0].contains("删除个人 Token"),
                        "Unconfigured token offers delete action");
            }
        } finally {
            runOnMainSync(activity::finish);
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
                if (visibleText[0].contains("网络不可用 · 请联网后重试")) {
                    return;
                }
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
        String targetPageId = new PageConfigStore(context).targetPageId();
        require(AppConfig.isConfiguredPage(targetPageId), "Target page unavailable");
        WolaiClient client = new WolaiClient(token);
        WolaiClient.setForceUnknownWriteForTest(true);
        try {
            client.createTextBlock(targetPageId, "X3_FAULT_INJECTION_NO_SEND");
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
        legacy.execSQL("CREATE TABLE local_notes ("
                + "id TEXT PRIMARY KEY,"
                + "text TEXT NOT NULL,"
                + "kind INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "state INTEGER NOT NULL,"
                + "error TEXT NOT NULL DEFAULT '',"
                + "last_attempt_at INTEGER NOT NULL DEFAULT 0)");
        legacy.execSQL("INSERT INTO local_notes "
                + "(id, text, kind, created_at, state, error, last_attempt_at) "
                + "VALUES ('migration-check', 'test', 0, 1, 2, '', 0)");
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

    private void verifyBackupRoundTrip() throws Exception {
        Context context = getTargetContext();
        String sourceName = "x3_wolai_notes_backup_source_test.db";
        String targetName = "x3_wolai_notes_backup_target_test.db";
        context.deleteDatabase(sourceName);
        context.deleteDatabase(targetName);
        NoteStore source = new NoteStore(context, sourceName);
        NoteStore target = new NoteStore(context, targetName);
        try {
            source.insertPending(new NoteItem("same-id", "旧备份", NoteItem.KIND_NOTE,
                    1L, NoteItem.STATE_PENDING, ""));
            source.insertPending(new NoteItem("new-id", "新增记录", NoteItem.KIND_TODO,
                    2L, NoteItem.STATE_PENDING, ""));
            source.recordRecentPage("page-1", "备份页面", 1L);
            byte[] backup = new NotesBackupManager(source).exportJson();
            String backupText = new String(backup, java.nio.charset.StandardCharsets.UTF_8);
            require(!backupText.contains("token_data"), "Backup exposed authorization data");

            target.insertPending(new NoteItem("same-id", "设备当前记录", NoteItem.KIND_NOTE,
                    3L, NoteItem.STATE_PENDING, ""));
            NotesBackupManager manager = new NotesBackupManager(target);
            require(manager.importJson(backup) == 2, "Backup import count mismatch");
            require("设备当前记录".equals(findById(target.recentLocal(10), "same-id").text),
                    "Older backup overwrote existing note");
            require(findById(target.recentLocal(10), "new-id") != null,
                    "New backup note was not merged");
            manager.importJson(backup);
            require(target.recentLocal(10).size() == 2, "Repeated import was not idempotent");
            require(target.recentPages(10).size() == 1, "Recent page backup was not restored");
        } finally {
            source.close();
            target.close();
            context.deleteDatabase(sourceName);
            context.deleteDatabase(targetName);
        }
    }
    private static String collectText(View view) {
        StringBuilder result = new StringBuilder();
        appendText(view, result);
        return result.toString();
    }

    private static void appendText(View view, StringBuilder result) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView) {
            result.append(((TextView) view).getText()).append('\n');
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            appendText(group.getChildAt(index), result);
        }
    }

    private static EditText findEditTextByHint(View view, String expectedHint) {
        if (view instanceof EditText) {
            CharSequence hint = ((EditText) view).getHint();
            if (hint != null && expectedHint.contentEquals(hint)) {
                return (EditText) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText match = findEditTextByHint(group.getChildAt(index), expectedHint);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
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
        PageConfigStore pages = new PageConfigStore(context);
        String targetPageId = pages.targetPageId();
        String favoritesPageId = pages.favoritesPageId();
        require(AppConfig.isConfiguredPage(targetPageId), "Target page unavailable");
        require(AppConfig.isConfiguredPage(favoritesPageId), "Favorites page unavailable");

        NoteStore store = new NoteStore(context);
        try {
            WolaiClient client = new WolaiClient(token);
            List<WolaiClient.RemoteBlock> before = client.fetchChildren(targetPageId);
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
                client.createTextBlock(targetPageId,
                        NoteFormatter.forWolai(local.kind, local.text));
            }
            store.markSynced(local.id);
            require(!containsId(store.pending(100), local.id), "Local queue completion failed");

            List<WolaiClient.RemoteBlock> after = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                after = client.fetchChildren(targetPageId);
                if (contains(after, TEST_TEXT)) {
                    break;
                }
                Thread.sleep(1000L);
            }
            require(after != null && contains(after, TEST_TEXT), "Remote round-trip failed");

            List<WolaiClient.RemoteBlock> favorites;
            try {
                favorites = client.fetchChildren(favoritesPageId);
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
            if (!favorite.isPageLink() || inspectedPages++ >= 20) {
                continue;
            }
            List<WolaiClient.RemoteBlock> blocks = client.fetchPage(favorite.targetPageId);
            for (WolaiClient.RemoteBlock block : blocks) {
                if (!"database".equals(block.type)) {
                    continue;
                }
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
            if (block.isPageLink()) {
                return block;
            }
        }
        return null;
    }

    private static boolean containsId(List<NoteItem> items, String expectedId) {
        return findById(items, expectedId) != null;
    }

    private static NoteItem findById(List<NoteItem> items, String expectedId) {
        for (NoteItem item : items) {
            if (expectedId.equals(item.id)) {
                return item;
            }
        }
        return null;
    }

    private static boolean contains(List<WolaiClient.RemoteBlock> blocks, String expected) {
        for (WolaiClient.RemoteBlock block : blocks) {
            if (expected.equals(block.text)) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        message = message.trim();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}
