package com.canace.x3wolainotes;

import android.content.Context;
import android.content.SharedPreferences;

final class PageConfigStore {
    private static final String PREFS = "page_config";
    private static final String TARGET = "target_page_id";
    private static final String FAVORITES = "favorites_page_id";
    private static final String CONFIG_SAVED_AT = "config_saved_at";
    private static final String CONFIG_EXPLICIT = "config_explicit";
    private static final String NOTICE_ACCEPTED = "privacy_notice_accepted";

    private final SharedPreferences preferences;

    PageConfigStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String targetPageId() {
        return configured(TARGET, BuildConfig.DEBUG ? BuildConfig.WOLAI_TARGET_PAGE_ID : "");
    }

    String favoritesPageId() {
        return configured(FAVORITES,
                BuildConfig.DEBUG ? BuildConfig.WOLAI_FAVORITES_PAGE_ID : "");
    }

    boolean save(String targetPageId, String favoritesPageId) {
        String target = normalizeId(targetPageId);
        String favorites = normalizeId(favoritesPageId);
        if ((!target.isEmpty() && !ApiInputPolicy.isValidResourceId(target))
                || (!favorites.isEmpty() && !ApiInputPolicy.isValidResourceId(favorites))) {
            return false;
        }
        return preferences.edit().putString(TARGET, target)
                .putString(FAVORITES, favorites)
                .putLong(CONFIG_SAVED_AT, System.currentTimeMillis())
                .putBoolean(CONFIG_EXPLICIT, true)
                .commit();
    }

    void clearPages() {
        preferences.edit().remove(TARGET).remove(FAVORITES).remove(CONFIG_SAVED_AT)
                .putBoolean(CONFIG_EXPLICIT, true).commit();
    }

    boolean hasConfiguredPages() {
        return !targetPageId().isEmpty() || !favoritesPageId().isEmpty();
    }

    long configSavedAt() {
        return preferences.getLong(CONFIG_SAVED_AT, 0L);
    }

    boolean hasAcceptedPrivacyNotice() {
        return preferences.getBoolean(NOTICE_ACCEPTED, false);
    }

    void acceptPrivacyNotice() {
        preferences.edit().putBoolean(NOTICE_ACCEPTED, true).apply();
    }

    private String configured(String key, String debugFallback) {
        String stored = normalizeId(preferences.getString(key, ""));
        if (preferences.getBoolean(CONFIG_EXPLICIT, false)) {
            return stored;
        }
        return stored.isEmpty() ? normalizeId(debugFallback) : stored;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }
}
