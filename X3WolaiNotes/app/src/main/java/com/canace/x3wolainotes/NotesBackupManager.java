package com.canace.x3wolainotes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class NotesBackupManager {
    private final NoteStore store;

    NotesBackupManager(NoteStore store) {
        this.store = store;
    }

    byte[] exportJson() throws JSONException {
        List<NoteItem> notes = store.allLocal(BackupPolicy.MAX_RECORDS + 1);
        List<RecentPage> pages = store.recentPages(BackupPolicy.MAX_RECENT_PAGES);
        BackupPolicy.validateNotes(notes);
        BackupPolicy.validateRecentPages(pages);
        JSONObject root = new JSONObject();
        root.put("product", "X3WolaiNotes");
        root.put("schema", BackupPolicy.SCHEMA_VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("warning", "包含本地笔记正文；未加密，不包含授权令牌、页面配置或在线页面内容");
        JSONArray local = new JSONArray();
        for (NoteItem item : notes) {
            JSONObject value = new JSONObject();
            value.put("id", item.id);
            value.put("text", item.text);
            value.put("kind", item.kind);
            value.put("createdAt", item.createdAt);
            value.put("state", item.state);
            value.put("error", item.error);
            value.put("lastAttemptAt", item.lastAttemptAt);
            local.put(value);
        }
        root.put("localNotes", local);
        JSONArray recent = new JSONArray();
        for (RecentPage page : pages) {
            JSONObject value = new JSONObject();
            value.put("id", page.id);
            value.put("title", page.title);
            value.put("accessedAt", page.accessedAt);
            recent.put(value);
        }
        root.put("recentPages", recent);
        byte[] result = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (result.length > BackupPolicy.MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("导出文件超过上限");
        }
        return result;
    }

    int importJson(byte[] bytes) throws JSONException {
        if (bytes == null || bytes.length == 0 || bytes.length > BackupPolicy.MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("备份文件大小无效");
        }
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"X3WolaiNotes".equals(root.optString("product"))
                || root.optInt("schema", -1) != BackupPolicy.SCHEMA_VERSION) {
            throw new IllegalArgumentException("备份类型或版本不受支持");
        }
        List<NoteItem> notes = parseNotes(root.optJSONArray("localNotes"));
        List<RecentPage> pages = parsePages(root.optJSONArray("recentPages"));
        BackupPolicy.validateNotes(notes);
        BackupPolicy.validateRecentPages(pages);
        store.mergeBackup(notes, pages);
        return notes.size();
    }

    private static List<NoteItem> parseNotes(JSONArray values) throws JSONException {
        if (values == null || values.length() > BackupPolicy.MAX_RECORDS) {
            throw new IllegalArgumentException("备份缺少本地记录或数量超限");
        }
        List<NoteItem> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            result.add(new NoteItem(value.getString("id"), value.getString("text"),
                    value.getInt("kind"), value.getLong("createdAt"),
                    value.getInt("state"), value.getString("error"),
                    value.getLong("lastAttemptAt")));
        }
        return result;
    }

    private static List<RecentPage> parsePages(JSONArray values) throws JSONException {
        if (values == null || values.length() > BackupPolicy.MAX_RECENT_PAGES) {
            throw new IllegalArgumentException("备份缺少最近页面或数量超限");
        }
        List<RecentPage> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            result.add(new RecentPage(value.getString("id"), value.getString("title"),
                    value.getLong("accessedAt")));
        }
        return result;
    }
}
