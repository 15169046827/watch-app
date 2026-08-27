package com.canace.x3wolainotes;

final class NoteItem {
    static final int KIND_NOTE = 0;
    static final int KIND_TODO = 1;
    static final int STATE_PENDING = 0;
    static final int STATE_SYNCED = 1;
    static final int STATE_FAILED = 2;

    final String id;
    final String text;
    final int kind;
    final long createdAt;
    final int state;
    final String error;

    NoteItem(String id, String text, int kind, long createdAt, int state, String error) {
        this.id = id;
        this.text = text;
        this.kind = kind;
        this.createdAt = createdAt;
        this.state = state;
        this.error = error;
    }
}

