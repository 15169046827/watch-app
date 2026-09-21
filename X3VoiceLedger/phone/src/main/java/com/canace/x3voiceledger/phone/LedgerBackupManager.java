package com.canace.x3voiceledger.phone;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LedgerBackupManager {
    static final int MAX_BACKUP_BYTES = 16 * 1024 * 1024;
    static final int MAX_ENTRY_COUNT = 50_000;
    private static final String TYPE = "x3voiceledger-backup";
    private static final int SCHEMA = 1;


    private LedgerBackupManager() { }

    static byte[] create(
            PhoneLedgerStore store, LedgerProfileStore profileStore, CategoryRuleStore ruleStore)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("type", TYPE);
        root.put("schema", SCHEMA);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("entries", store.exportEntries());
        root.put("profile", profileStore.exportData());
        root.put("categoryRules", ruleStore.exportData());
        root.put(
                "notIncluded",
                new JSONArray(
                        new String[] {
                            "systemPermissions",
                            "bluetoothPairing",
                            "screenshotScanCursor",
                            "paymentPageContent",
                            "credentials"
                        }));
        byte[] data = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BACKUP_BYTES) {
            throw new IllegalStateException("备份超过 16 MiB，请先清理不再需要的历史账单");
        }
        return data;
    }

    static void write(ContentResolver resolver, Uri uri, byte[] data) throws IOException {
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IOException("无法打开所选导出位置");
            }
            output.write(data);
            output.flush();
        }
    }

    static ParsedBackup read(ContentResolver resolver, Uri uri) throws IOException, JSONException {
        byte[] data;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("无法读取所选备份文件");
            }
            data = readBounded(input);
        }
        JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        validate(root);
        return new ParsedBackup(root);
    }

    static int restore(
            ParsedBackup backup,
            PhoneLedgerStore store,
            LedgerProfileStore profileStore,
            CategoryRuleStore ruleStore)
            throws JSONException {
        int restored = store.restoreEntries(backup.root.getJSONArray("entries"));
        profileStore.restore(backup.root.getJSONObject("profile"));
        ruleStore.restore(backup.root.getJSONObject("categoryRules"));
        return restored;
    }

    static void validate(JSONObject root) throws JSONException {
        if (!BackupPolicy.supports(root.optString("type"), root.optInt("schema"))) {
            throw new JSONException("不是受支持的记账备份，或备份版本过新");
        }
        JSONArray entries = root.getJSONArray("entries");
        if (entries.length() > MAX_ENTRY_COUNT) {
            throw new JSONException("备份账单数量超过 50000 条上限");
        }
        for (int i = 0; i < entries.length(); i++) {
            validateEntry(entries.getJSONObject(i));
        }
        JSONObject profile = root.getJSONObject("profile");
        JSONObject rules = root.getJSONObject("categoryRules");
        if (!"ledgerProfile".equals(profile.optString("type"))
                || profile.optInt("schema") < 1
                || profile.optInt("schema") > 2
                || rules.optInt("schema") != 1) {
            throw new JSONException("备份中的配置版本不受支持");
        }
        if (profile.toString().length() > 1024 * 1024
                || rules.toString().length() > 4 * 1024 * 1024) {
            throw new JSONException("备份中的配置或分类规则异常过大");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_BACKUP_BYTES) {
                throw new IOException("备份文件超过 16 MiB 上限");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateEntry(JSONObject entry) throws JSONException {
        String id = entry.optString("id").trim();
        if (!BackupPolicy.isValidEntryIdentity(id, entry.optLong("updatedAt"))) {
            throw new JSONException("备份中存在无效账单标识或修订时间");
        }
        String[] fields = {
            "note",
            "category",
            "categoryParent",
            "categoryChild",
            "account",
            "bookName",
            "tags",
            "source",
            "locationLabel",
            "yimuParent",
            "yimuChild"
        };
        for (String field : fields) {
            if (!BackupPolicy.isValidText(entry.optString(field))) {
                throw new JSONException("备份账单字段异常过长：" + field);
            }
        }
    }

    static final class ParsedBackup {
        final JSONObject root;
        final int entryCount;
        final int ruleCount;

        ParsedBackup(JSONObject root) {
            this.root = root;
            entryCount = root.optJSONArray("entries").length();
            JSONObject rules = root.optJSONObject("categoryRules");
            JSONArray values = rules == null ? null : rules.optJSONArray("rules");
            ruleCount = values == null ? 0 : values.length();
        }
    }
}
