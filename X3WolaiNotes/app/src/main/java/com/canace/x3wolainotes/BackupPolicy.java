package com.canace.x3wolainotes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BackupPolicy {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    static final int MAX_RECORDS = 50_000;
    static final int MAX_ID_LENGTH = 200;
    static final int MAX_TITLE_LENGTH = 240;
    static final int MAX_ERROR_LENGTH = 120;
    static final int MAX_NOTE_LENGTH = 240;
    static final int MAX_RECENT_PAGES = 10;

    static void validateNotes(List<NoteItem> notes) {
        if (notes.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("记录数量超过上限");
        }
        Set<String> ids = new HashSet<>();
        for (NoteItem item : notes) {
            if (!validId(item.id) || !ids.add(item.id)) {
                throw new IllegalArgumentException("记录标识无效或重复");
            }
            if (item.text == null || item.text.length() > MAX_NOTE_LENGTH
                    || (item.kind != NoteItem.KIND_NOTE && item.kind != NoteItem.KIND_TODO)
                    || item.createdAt < 0L || item.lastAttemptAt < 0L
                    || item.state < NoteItem.STATE_PENDING || item.state > NoteItem.STATE_UNCERTAIN
                    || item.error == null || item.error.length() > MAX_ERROR_LENGTH) {
                throw new IllegalArgumentException("记录内容无效");
            }
        }
    }

    static void validateRecentPages(List<RecentPage> pages) {
        if (pages.size() > MAX_RECENT_PAGES) {
            throw new IllegalArgumentException("最近页面数量超过上限");
        }
        Set<String> ids = new HashSet<>();
        for (RecentPage page : pages) {
            if (!validId(page.id) || !ids.add(page.id) || page.title == null
                    || page.title.isEmpty() || page.title.length() > MAX_TITLE_LENGTH
                    || page.accessedAt < 0L) {
                throw new IllegalArgumentException("最近页面内容无效");
            }
        }
    }

    private static boolean validId(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_ID_LENGTH
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private BackupPolicy() {
    }
}
