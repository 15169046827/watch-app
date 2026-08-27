package com.canace.x3wolainotes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.util.List;
import java.util.UUID;

public final class MvpTestRunner extends Instrumentation {
    private static final String TEST_TEXT = "X3_MVP_TEST_20260817";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            runReadWriteRoundTrip();
            result.putString("stream", "V3_READ_WRITE_FAVORITES_DATABASE_OK\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "MVP_READ_WRITE_FAILED: " + safeMessage(error) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runReadWriteRoundTrip() throws Exception {
        Context context = getTargetContext();
        SecretStore secrets = new SecretStore(context);
        String token = secrets.loadToken();
        require(token != null && token.length() >= 20, "Provisioned token unavailable");

        NoteStore store = new NoteStore(context);
        try {
            WolaiClient client = new WolaiClient(token);
            List<WolaiClient.RemoteBlock> before = client.fetchChildren(AppConfig.TARGET_PAGE_ID);
            boolean alreadyPresent = contains(before, TEST_TEXT);

            NoteItem local = new NoteItem(UUID.randomUUID().toString(), TEST_TEXT,
                    NoteItem.KIND_NOTE, System.currentTimeMillis(), NoteItem.STATE_PENDING, "");
            store.insertPending(local);
            require(containsId(store.pending(100), local.id), "Local queue insert failed");

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
        for (NoteItem item : items) {
            if (expectedId.equals(item.id)) return true;
        }
        return false;
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
