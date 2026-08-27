package com.canace.x3wolainotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class WolaiClient {
    static final class RemoteBlock {
        final String id;
        final String type;
        final String text;
        final long editedAt;
        final String targetPageId;
        final boolean hasChildren;
        final boolean checked;
        final int depth;

        RemoteBlock(String id, String type, String text, long editedAt,
                    String targetPageId, boolean hasChildren, boolean checked, int depth) {
            this.id = id;
            this.type = type;
            this.text = text;
            this.editedAt = editedAt;
            this.targetPageId = targetPageId;
            this.hasChildren = hasChildren;
            this.checked = checked;
            this.depth = depth;
        }

        boolean isPageLink() {
            return targetPageId != null && !targetPageId.isEmpty();
        }

        RemoteBlock atDepth(int value) {
            return new RemoteBlock(id, type, text, editedAt, targetPageId, hasChildren, checked, value);
        }
    }

    static final class DatabaseCell {
        final String column;
        final String type;
        final String value;

        DatabaseCell(String column, String type, String value) {
            this.column = column;
            this.type = type;
            this.value = value;
        }
    }

    static final class DatabaseRow {
        final String pageId;
        final List<DatabaseCell> cells;

        DatabaseRow(String pageId, List<DatabaseCell> cells) {
            this.pageId = pageId;
            this.cells = Collections.unmodifiableList(cells);
        }
    }

    static final class DatabaseTable {
        final List<String> columnOrder;
        final List<DatabaseRow> rows;
        final boolean hasMore;
        final String error;

        DatabaseTable(List<String> columnOrder, List<DatabaseRow> rows,
                      boolean hasMore, String error) {
            this.columnOrder = Collections.unmodifiableList(columnOrder);
            this.rows = Collections.unmodifiableList(rows);
            this.hasMore = hasMore;
            this.error = error;
        }

        static DatabaseTable failed(String message) {
            return new DatabaseTable(new ArrayList<>(), new ArrayList<>(), false, message);
        }
    }

    static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }

        ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String token;

    WolaiClient(String token) {
        this.token = token;
    }

    List<RemoteBlock> fetchChildren(String pageId) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = open("GET", "/blocks/" + pageId + "/children");
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) throw apiError(status, body);
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            List<RemoteBlock> result = new ArrayList<>();
            if (data == null) return result;
            for (int index = 0; index < data.length(); index++) {
                JSONObject block = data.optJSONObject(index);
                if (block == null) continue;
                String id = block.optString("id", "item-" + index);
                String type = block.optString("type", "text");
                Object content = block.opt("content");
                String targetPageId = "page".equals(type) ? id : extractPageReference(content);
                result.add(new RemoteBlock(id, type, extractText(content),
                        block.optLong("edited_at", 0L), targetPageId, hasChildren(block),
                        block.optBoolean("checked", false), 0));
            }
            return result;
        } catch (IOException | JSONException error) {
            throw new ApiException("读取 Wolai 失败", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    List<RemoteBlock> fetchPage(String pageId) throws ApiException {
        List<RemoteBlock> result = new ArrayList<>();
        appendChildren(pageId, 0, result);
        return result;
    }

    DatabaseTable fetchDatabase(String databaseId) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = open("GET", "/databases/" + databaseId);
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) throw apiError(status, body);

            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new ApiException("表格响应缺少 data");

            List<String> columns = new ArrayList<>();
            JSONArray columnOrder = data.optJSONArray("column_order");
            if (columnOrder != null) {
                for (int index = 0; index < columnOrder.length(); index++) {
                    String column = columnOrder.optString(index, "").trim();
                    if (!column.isEmpty()) columns.add(column);
                }
            }

            List<DatabaseRow> rows = new ArrayList<>();
            JSONArray remoteRows = data.optJSONArray("rows");
            if (remoteRows != null) {
                for (int index = 0; index < remoteRows.length(); index++) {
                    JSONObject remoteRow = remoteRows.optJSONObject(index);
                    if (remoteRow == null) continue;
                    JSONObject rowData = remoteRow.optJSONObject("data");
                    List<DatabaseCell> cells = new ArrayList<>();
                    if (rowData != null) {
                        for (String column : columns) {
                            JSONObject remoteCell = rowData.optJSONObject(column);
                            String type = remoteCell == null ? "" : remoteCell.optString("type", "");
                            Object rawValue = remoteCell == null ? null : remoteCell.opt("value");
                            cells.add(new DatabaseCell(column, type, formatCellValue(rawValue)));
                        }
                    }
                    rows.add(new DatabaseRow(remoteRow.optString("page_id", "").trim(), cells));
                }
            }
            return new DatabaseTable(columns, rows, root.optBoolean("has_more", false), "");
        } catch (IOException | JSONException error) {
            throw new ApiException("读取 Wolai 表格失败", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void appendChildren(String parentId, int depth, List<RemoteBlock> result)
            throws ApiException {
        if (depth >= AppConfig.MAX_READER_DEPTH || result.size() >= AppConfig.MAX_READER_BLOCKS) return;
        for (RemoteBlock block : fetchChildren(parentId)) {
            if (result.size() >= AppConfig.MAX_READER_BLOCKS) return;
            result.add(block.atDepth(depth));
            if (block.hasChildren && !block.isPageLink()) {
                appendChildren(block.id, depth + 1, result);
            }
        }
    }

    void createTextBlock(String pageId, String text) throws ApiException {
        HttpURLConnection connection = null;
        try {
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("content", text);
            JSONArray blocks = new JSONArray();
            blocks.put(block);
            JSONObject payload = new JSONObject();
            payload.put("parent_id", pageId);
            payload.put("blocks", blocks);

            connection = open("POST", "/blocks");
            connection.setDoOutput(true);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) throw apiError(status, body);
        } catch (IOException | JSONException error) {
            throw new ApiException("写入 Wolai 失败", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(AppConfig.API_BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(16000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", token);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "X3WolaiNotes/0.3 Android");
        return connection;
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > 1024 * 1024) break;
            }
            return output.toString("UTF-8");
        }
    }

    private static ApiException apiError(int status, String body) {
        String message = "Wolai 返回 " + status;
        try {
            String remote = new JSONObject(body).optString("message", "").trim();
            if (!remote.isEmpty()) message = remote;
        } catch (JSONException ignored) {
            // Keep the status-only error; never expose a full HTML error body.
        }
        return new ApiException(message);
    }

    private static String extractText(Object content) {
        if (content instanceof String) return ((String) content).trim();
        if (content instanceof JSONObject) {
            return ((JSONObject) content).optString("title", "").trim();
        }
        if (!(content instanceof JSONArray)) return "";
        JSONArray items = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < items.length(); index++) {
            Object item = items.opt(index);
            String fragment = "";
            if (item instanceof JSONObject) fragment = ((JSONObject) item).optString("title", "");
            else if (item instanceof String) fragment = (String) item;
            if (fragment.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(fragment);
        }
        return text.toString().trim();
    }

    private static String extractPageReference(Object content) {
        if (content instanceof JSONObject) {
            JSONObject item = (JSONObject) content;
            if ("bi_link".equals(item.optString("type"))) {
                String blockId = item.optString("block_id", "").trim();
                if (!blockId.isEmpty()) return blockId;
                String refId = item.optString("ref_id", "").trim();
                if (!refId.isEmpty()) return refId;
            }
            return "";
        }
        if (!(content instanceof JSONArray)) return "";
        JSONArray items = (JSONArray) content;
        for (int index = 0; index < items.length(); index++) {
            String refId = extractPageReference(items.opt(index));
            if (!refId.isEmpty()) return refId;
        }
        return "";
    }

    private static boolean hasChildren(JSONObject block) {
        if (block.optBoolean("has_children", false)) return true;
        Object children = block.opt("children");
        if (children instanceof JSONArray) return ((JSONArray) children).length() > 0;
        if (!(children instanceof JSONObject)) return false;
        Object ids = ((JSONObject) children).opt("ids");
        return ids instanceof JSONArray && ((JSONArray) ids).length() > 0;
    }

    private static String formatCellValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return ((String) value).trim();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < array.length(); index++) {
                String item = formatCellValue(array.opt(index));
                if (item.isEmpty()) continue;
                if (result.length() > 0) result.append("、");
                result.append(item);
            }
            return result.toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : new String[]{"title", "name", "text", "value"}) {
                String preferred = formatCellValue(object.opt(key));
                if (!preferred.isEmpty()) return preferred;
            }
            JSONArray names = object.names();
            if (names == null) return "";
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < names.length(); index++) {
                String item = formatCellValue(object.opt(names.optString(index)));
                if (item.isEmpty()) continue;
                if (result.length() > 0) result.append("、");
                result.append(item);
            }
            return result.toString();
        }
        return String.valueOf(value).trim();
    }
}
