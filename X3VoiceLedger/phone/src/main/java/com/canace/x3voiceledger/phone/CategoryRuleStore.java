package com.canace.x3voiceledger.phone;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class CategoryRuleStore {
    static final int MODE_SMART = 0;
    static final int MODE_ALWAYS = 1;
    static final int MODE_AUTO = 2;
    private static final String RULE_PREFIX = "rule:";
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
        return decodeMapping(stored);
    }

    void remember(PhoneLedgerStore.Entry entry, YimuCategoryMapper.Mapping mapping) {
        preferences.edit().putString(ruleKey(entry), mapping.parent + SEPARATOR + mapping.child).apply();
    }

    List<Rule> rules() {
        List<Rule> result = new ArrayList<>();
        for (Map.Entry<String, ?> item : preferences.getAll().entrySet()) {
            String key = item.getKey();
            if (!key.startsWith(RULE_PREFIX) || !(item.getValue() instanceof String)) continue;
            YimuCategoryMapper.Mapping mapping = decodeMapping((String) item.getValue());
            if (mapping == null) continue;
            String source = key.substring(RULE_PREFIX.length());
            int split = source.indexOf(':');
            String category = split < 0 ? source : source.substring(0, split);
            String text = split < 0 ? "" : source.substring(split + 1);
            result.add(new Rule(key, category, text, mapping));
        }
        Collections.sort(result, Comparator.comparing((Rule value) -> value.sourceCategory)
                .thenComparing(value -> value.sourceText));
        return result;
    }

    int ruleCount() {
        return rules().size();
    }

    boolean delete(String storageKey) {
        if (storageKey == null || !storageKey.startsWith(RULE_PREFIX)
                || !preferences.contains(storageKey)) return false;
        preferences.edit().remove(storageKey).apply();
        return true;
    }

    int clearRules() {
        SharedPreferences.Editor editor = preferences.edit();
        int count = 0;
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(RULE_PREFIX)) continue;
            editor.remove(key);
            count++;
        }
        if (count > 0) editor.apply();
        return count;
    }

    private static YimuCategoryMapper.Mapping decodeMapping(String stored) {
        if (stored == null) return null;
        int split = stored.indexOf(SEPARATOR);
        if (split < 0) return null;
        return new YimuCategoryMapper.Mapping(stored.substring(0, split), stored.substring(split + 1));
    }

    private static String ruleKey(PhoneLedgerStore.Entry entry) {
        String normalized = entry.note.toLowerCase().replaceAll("[\\s，,。.!！?？:：;；]", "");
        return RULE_PREFIX + entry.category.trim() + ":" + normalized;
    }

    static final class Rule {
        final String storageKey;
        final String sourceCategory;
        final String sourceText;
        final YimuCategoryMapper.Mapping mapping;

        Rule(String storageKey, String sourceCategory, String sourceText,
             YimuCategoryMapper.Mapping mapping) {
            this.storageKey = storageKey;
            this.sourceCategory = sourceCategory;
            this.sourceText = sourceText;
            this.mapping = mapping;
        }
    }
}
