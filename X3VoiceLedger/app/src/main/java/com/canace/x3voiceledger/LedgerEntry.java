package com.canace.x3voiceledger;

final class LedgerEntry {
    final String id;
    final long createdAt;
    final long updatedAt;
    final long transactionTime;
    final long amountCents;
    final String note;
    final String category;
    final String categoryParent;
    final String categoryChild;
    final String account;
    final String bookName;
    final String tags;
    final String rawText;
    final String source;
    final Double latitude;
    final Double longitude;
    final Float locationAccuracy;
    final Long locationTime;
    final String locationLabel;

    LedgerEntry(String id, long createdAt, long updatedAt, long transactionTime,
                long amountCents, String note, String category, String categoryParent,
                String categoryChild, String account, String bookName, String tags, String rawText,
                String source,
                Double latitude, Double longitude, Float locationAccuracy,
                Long locationTime, String locationLabel) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.transactionTime = transactionTime;
        this.amountCents = amountCents;
        this.note = note;
        this.category = category;
        this.categoryParent = categoryParent;
        this.categoryChild = categoryChild;
        this.account = account;
        this.bookName = bookName;
        this.tags = tags;
        this.rawText = rawText;
        this.source = source;
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationAccuracy = locationAccuracy;
        this.locationTime = locationTime;
        this.locationLabel = locationLabel;
    }
}
