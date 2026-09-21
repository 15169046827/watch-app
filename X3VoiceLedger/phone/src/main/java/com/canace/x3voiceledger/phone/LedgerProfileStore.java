package com.canace.x3voiceledger.phone;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LedgerProfileStore {
    private static final String PREFS = "ledger_profile";
    private static final String ACCOUNTS = "accounts";
    private static final String DEFAULT_ACCOUNT = "default_account";
    private static final String BOOKS = "books";
    private static final String BOOK_NAME = "book_name";
    private static final String AVAILABLE_TAGS = "available_tags";
    private static final String TAGS = "tags";
    private static final String CATEGORIES = "categories";
    private static final String REVISION = "revision";
    private final SharedPreferences preferences;

    LedgerProfileStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<String> accounts() {
        return lines(preferences.getString(ACCOUNTS, ""));
    }

    String accountsText() {
        return joinLines(accounts());
    }

    String defaultAccount() {
        return preferences.getString(DEFAULT_ACCOUNT, "").trim();
    }

    List<String> books() {
        List<String> values = lines(preferences.getString(BOOKS, ""));
        if (values.isEmpty() && !bookName().isEmpty()) {
            values.add(bookName());
        }
        return values;
    }

    String booksText() {
        return joinLines(books());
    }

    String bookName() {
        return preferences.getString(BOOK_NAME, "").trim();
    }

    List<String> availableTags() {
        List<String> values = lines(preferences.getString(AVAILABLE_TAGS, ""));
        if (values.isEmpty()) {
            values.addAll(splitTags(tags()));
        }
        return values;
    }

    String availableTagsText() {
        return joinLines(availableTags());
    }

    String tags() {
        return preferences.getString(TAGS, "").trim();
    }

    List<YimuCategoryMapper.Parent> categories() {
        String raw = preferences.getString(CATEGORIES, "");
        if (raw.isEmpty()) {
            return YimuCategoryMapper.catalog();
        }
        try {
            List<YimuCategoryMapper.Parent> values = decodeCategories(new JSONArray(raw));
            return values.isEmpty() ? YimuCategoryMapper.catalog() : values;
        } catch (JSONException error) {
            return YimuCategoryMapper.catalog();
        }
    }

    void save(
            String accounts,
            String defaultAccount,
            String books,
            String bookName,
            String availableTags,
            String tags) {
        List<String> accountValues = lines(accounts);
        addIfMissing(accountValues, defaultAccount);
        List<String> bookValues = lines(books);
        addIfMissing(bookValues, bookName);
        List<String> tagValues = lines(availableTags);
        for (String value : splitTags(tags)) {
            addIfMissing(tagValues, value);
        }
        preferences
                .edit()
                .putString(ACCOUNTS, joinLines(accountValues))
                .putString(DEFAULT_ACCOUNT, defaultAccount.trim())
                .putString(BOOKS, joinLines(bookValues))
                .putString(BOOK_NAME, bookName.trim())
                .putString(AVAILABLE_TAGS, joinLines(tagValues))
                .putString(TAGS, tags.trim())
                .putLong(REVISION, System.currentTimeMillis())
                .apply();
    }

    void merge(LedgerProfileImporter.Result imported) {
        List<String> accountValues = accounts();
        merge(accountValues, imported.accounts);
        List<String> bookValues = books();
        merge(bookValues, imported.books);
        List<String> tagValues = availableTags();
        merge(tagValues, imported.tags);
        List<YimuCategoryMapper.Parent> categoryValues =
                mergeCategories(categories(), imported.categories);
        String defaultAccount = defaultAccount();
        if (defaultAccount.isEmpty() && !imported.accounts.isEmpty()) {
            defaultAccount = imported.accounts.get(0);
        }
        String defaultBook = bookName();
        if (defaultBook.isEmpty() && !imported.books.isEmpty()) {
            defaultBook = imported.books.get(0);
        }
        preferences
                .edit()
                .putString(ACCOUNTS, joinLines(accountValues))
                .putString(DEFAULT_ACCOUNT, defaultAccount)
                .putString(BOOKS, joinLines(bookValues))
                .putString(BOOK_NAME, defaultBook)
                .putString(AVAILABLE_TAGS, joinLines(tagValues))
                .putString(CATEGORIES, encodeCategories(categoryValues).toString())
                .putLong(REVISION, System.currentTimeMillis())
                .apply();
    }

    String accountForChannel(String channel) {
        for (String account : accounts()) {
            if (account.equals(channel) || account.contains(channel) || channel.contains(account)) {
                return account;
            }
        }
        return "";
    }

    byte[] payload() {
        try {
            JSONObject object = new JSONObject();
            object.put("type", "ledgerProfile");
            object.put("schema", 2);
            object.put("revision", preferences.getLong(REVISION, 0L));
            object.put("accounts", new JSONArray(accounts()));
            object.put("defaultAccount", defaultAccount());
            object.put("books", new JSONArray(books()));
            object.put("bookName", bookName());
            object.put("availableTags", new JSONArray(availableTags()));
            object.put("tags", tags());
            object.put("categories", encodeCategories(categories()));
            return object.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            return new byte[0];
        }
    }

    JSONObject exportData() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("type", "ledgerProfile");
        object.put("schema", 2);
        object.put("revision", preferences.getLong(REVISION, 0L));
        object.put("accounts", new JSONArray(accounts()));
        object.put("defaultAccount", defaultAccount());
        object.put("books", new JSONArray(books()));
        object.put("bookName", bookName());
        object.put("availableTags", new JSONArray(availableTags()));
        object.put("tags", tags());
        object.put("categories", encodeCategories(categories()));
        return object;
    }

    void restore(JSONObject object) {
        List<String> accounts = jsonStrings(object.optJSONArray("accounts"));
        List<String> books = jsonStrings(object.optJSONArray("books"));
        List<String> tags = jsonStrings(object.optJSONArray("availableTags"));
        JSONArray categories = object.optJSONArray("categories");
        preferences
                .edit()
                .clear()
                .putString(ACCOUNTS, joinLines(accounts))
                .putString(DEFAULT_ACCOUNT, object.optString("defaultAccount").trim())
                .putString(BOOKS, joinLines(books))
                .putString(BOOK_NAME, object.optString("bookName").trim())
                .putString(AVAILABLE_TAGS, joinLines(tags))
                .putString(TAGS, object.optString("tags").trim())
                .putString(CATEGORIES, categories == null ? "[]" : categories.toString())
                .putLong(REVISION, object.optLong("revision", System.currentTimeMillis()))
                .apply();
    }

    void clear() {
        preferences.edit().clear().apply();
    }

    String summary() {
        return "账户 "
                + accounts().size()
                + " 个 · 分类 "
                + categories().size()
                + " 组 · 账本 "
                + books().size()
                + " 个 · 标签 "
                + availableTags().size()
                + " 个"
                + (defaultAccount().isEmpty() ? "" : " · 默认 " + defaultAccount());
    }

    private static List<String> jsonStrings(JSONArray values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int i = 0; i < values.length(); i++) {
            addIfMissing(result, values.optString(i));
        }
        return result;
    }

    private static List<String> lines(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw.split("[\\r\\n,，]+")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return new ArrayList<>(values);
    }

    private static List<String> splitTags(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw.split("[\\s,，;；]+")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return new ArrayList<>(values);
    }

    private static String joinLines(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(value.trim());
        }
        return result.toString();
    }

    private static void addIfMissing(List<String> values, String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        if (!value.isEmpty() && !values.contains(value)) {
            values.add(value);
        }
    }

    private static void merge(List<String> target, List<String> source) {
        for (String value : source) {
            addIfMissing(target, value);
        }
    }

    private static List<YimuCategoryMapper.Parent> mergeCategories(
            List<YimuCategoryMapper.Parent> base, List<YimuCategoryMapper.Parent> imported) {
        Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        for (YimuCategoryMapper.Parent parent : base) {
            values.put(parent.name, new LinkedHashSet<>(parent.children));
        }
        for (YimuCategoryMapper.Parent parent : imported) {
            LinkedHashSet<String> children = values.get(parent.name);
            if (children == null) {
                children = new LinkedHashSet<>();
                values.put(parent.name, children);
            }
            children.addAll(parent.children);
        }
        List<YimuCategoryMapper.Parent> result = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : values.entrySet()) {
            result.add(
                    new YimuCategoryMapper.Parent(
                            entry.getKey(), new ArrayList<>(entry.getValue())));
        }
        return result;
    }

    private static JSONArray encodeCategories(List<YimuCategoryMapper.Parent> values) {
        JSONArray result = new JSONArray();
        for (YimuCategoryMapper.Parent parent : values) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", parent.name);
                item.put("children", new JSONArray(parent.children));
                result.put(item);
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    private static List<YimuCategoryMapper.Parent> decodeCategories(JSONArray values) {
        List<YimuCategoryMapper.Parent> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name").trim();
            if (name.isEmpty()) {
                continue;
            }
            List<String> children = new ArrayList<>();
            JSONArray childValues = item.optJSONArray("children");
            if (childValues != null) {
                for (int j = 0; j < childValues.length(); j++) {
                    addIfMissing(children, childValues.optString(j));
                }
            }
            result.add(new YimuCategoryMapper.Parent(name, children));
        }
        return result;
    }
}
