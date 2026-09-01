package com.canace.x3wolainotes;

final class RecentPage {
    final String id;
    final String title;
    final long accessedAt;

    RecentPage(String id, String title, long accessedAt) {
        this.id = id;
        this.title = title;
        this.accessedAt = accessedAt;
    }
}
