package com.canace.x3wolainotes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BackupPolicySelfTest {
    private BackupPolicySelfTest() {
    }

    public static void main(String[] args) {
        NoteItem note = new NoteItem("note-1", "正文", NoteItem.KIND_NOTE, 1L,
                NoteItem.STATE_PENDING, "", 0L);
        BackupPolicy.validateNotes(Collections.singletonList(note));
        BackupPolicy.validateRecentPages(Collections.singletonList(
                new RecentPage("page-1", "页面", 1L)));

        expectFailure(() -> BackupPolicy.validateNotes(Arrays.asList(note, note)),
                "duplicate note id accepted");
        expectFailure(() -> BackupPolicy.validateNotes(Collections.singletonList(
                new NoteItem("bad", repeat('x', BackupPolicy.MAX_NOTE_LENGTH + 1),
                        NoteItem.KIND_NOTE, 1L, NoteItem.STATE_PENDING, "", 0L))),
                "oversized note accepted");
        List<RecentPage> tooManyPages = new ArrayList<>();
        for (int index = 0; index <= BackupPolicy.MAX_RECENT_PAGES; index++) {
            tooManyPages.add(new RecentPage("page-" + index, "页面", index));
        }
        expectFailure(() -> BackupPolicy.validateRecentPages(tooManyPages),
                "oversized recent page list accepted");
        System.out.println("BACKUP_POLICY_SELF_TEST_OK");
    }

    private static String repeat(char value, int length) {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }
}
