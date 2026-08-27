package com.canace.x3voiceledger.phone;

import android.content.Context;
import android.content.SharedPreferences;

final class CategoryRuleStore {
    static final int MODE_SMART = 0;
    static final int MODE_ALWAYS = 1;
    static final int MODE_AUTO = 2;
    private static final String SEPARATOR = "\u001f";
    private final SharedPreferences preferences;

    CategoryRuleStore(Context context) {
        preferences = context.getSharedPreferences("category_rules", Context.MODE_PRIVATE);
    }

    int mode() {
        return preferences.getInt("confirmation_mode", MODE_SMART);
    }

    void setMode(int mode) {
        preferences.edit().putInt("confirmation_mode", mode).apply();
    }

    YimuCategoryMapper.Mapping find(PhoneLedgerStore.Entry entry) {
        String stored = preferences.getString(ruleKey(entry), null);
        if (stored == null) return null;
        int split = stored.indexOf(SEPARATOR);
        if (split < 0) return null;
        return new YimuCategoryMapper.Mapping(stored.substring(0, split), stored.substring(split + 1));
    }

    void remember(PhoneLedgerStore.Entry entry, YimuCategoryMapper.Mapping mapping) {
        preferences.edit().putString(ruleKey(entry), mapping.parent + SEPARATOR + mapping.child).apply();
    }

    private static String ruleKey(PhoneLedgerStore.Entry entry) {
        String normalized = entry.note.toLowerCase().replaceAll("[\\s，,。.!！?？:：;；]", "");
        return "rule:" + entry.category.trim() + ":" + normalized;
    }
}
