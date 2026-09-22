package com.canace.x3wolainotes;

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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class WolaiClient {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static volatile boolean forceOfflineForTest;
    private static volatile boolean forceUnknownWriteForTest;

    static void setForceOfflineForTest(boolean enabled) {
        forceOfflineForTest = BuildConfig.DEBUG && enabled;
    }

    static void setForceUnknownWriteForTest(boolean enabled) {
        forceUnknownWriteForTest = BuildConfig.DEBUG && enabled;
    }

    static void resetTestFaults() {
        forceOfflineForTest = false;
        forceUnknownWriteForTest = false;
    }

    static final class RemoteBlock {
        final String id;
        final String type;
        final String text;
        final long createdAt;
        final long editedAt;
        final String targetPageId;
        final boolean hasChildren;
        final boolean checked;
        final int depth;

        RemoteBlock(String id, String type, String text, long createdAt, long editedAt,
                    String targetPageId, boolean hasChildren, boolean checked, int depth) {
            this.id = id;
            this.type = type;
            this.text = text;
            this.createdAt = createdAt;
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
            return new RemoteBlock(id, type, text, createdAt, editedAt, targetPageId,
                    hasChildren, checked, value);
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
        private final int statusCode;
        private final boolean outcomeUnknown;

        ApiException(String message) {
            this(message, null, 0, false);
        }

        ApiException(String message, Throwable cause) {
            this(message, cause, 0, false);
        }

        private ApiException(String message, Throwable cause, int statusCode,
                             boolean outcomeUnknown) {
            super(message, cause);
            this.statusCode = statusCode;
            this.outcomeUnknown = outcomeUnknown;
        }

        int statusCode() {
            return statusCode;
        }

        boolean isOutcomeUnknown() {
            return outcomeUnknown;
        }
    }

    private final String token;

    WolaiClient(String token) {
        this.token = token;
    }

    static int statusOf(Exception error) {
        return error instanceof ApiException ? ((ApiException) error).statusCode() : 0;
    }

    List<RemoteBlock> fetchChildren(String pageId) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = open("GET", "/blocks/" + requireResourceId(pageId) + "/children");
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw apiError(status, body);
            }
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            List<RemoteBlock> result = new ArrayList<>();
            if (data == null) {
                return result;
            }
            int blockCount = Math.min(data.length(), AppConfig.MAX_READER_BLOCKS);
            for (int index = 0; index < blockCount; index++) {
                JSONObject block = data.optJSONObject(index);
                if (block == null) {
                    continue;
                }
                String id = block.optString("id", "item-" + index);
                String type = block.optString("type", "text");
                Object content = block.opt("content");
                String targetPageId = "page".equals(type) ? id : extractPageReference(content);
                targetPageId = safeResourceId(targetPageId);
                boolean canLoadChildren = ApiInputPolicy.isValidResourceId(id) && hasChildren(block);
                result.add(new RemoteBlock(id, type,
                        ApiInputPolicy.boundedText(extractText(content),
                                AppConfig.MAX_REMOTE_TEXT_LENGTH),
                        epochMillis(block.optLong("created_at", 0L)),
                        epochMillis(block.optLong("edited_at", 0L)),
                        targetPageId, canLoadChildren, block.optBoolean("checked", false), 0));
            }
            return result;
        } catch (IOException | JSONException error) {
            throw new ApiException("读取 Wolai 失败", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
            connection = open("GET", "/databases/" + requireResourceId(databaseId));
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw apiError(status, body);
            }

            return parseDatabase(new JSONObject(body));
        } catch (IOException | JSONException error) {
            throw new ApiException("读取 Wolai 表格失败", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static DatabaseTable parseDatabase(JSONObject root) throws ApiException {
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new ApiException("表格响应缺少 data");
        }
        List<String> columns = parseColumns(data.optJSONArray("column_order"));
        List<DatabaseRow> rows = parseRows(data.optJSONArray("rows"), columns);
        return new DatabaseTable(columns, rows, root.optBoolean("has_more", false), "");
    }

    private static List<String> parseColumns(JSONArray columnOrder) {
        List<String> columns = new ArrayList<>();
        if (columnOrder == null) {
            return columns;
        }
        int columnCount = Math.min(columnOrder.length(), AppConfig.MAX_DATABASE_COLUMNS);
        for (int index = 0; index < columnCount; index++) {
            String column = ApiInputPolicy.boundedText(columnOrder.optString(index, ""),
                    AppConfig.MAX_NOTE_LENGTH);
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static List<DatabaseRow> parseRows(JSONArray remoteRows, List<String> columns) {
        List<DatabaseRow> rows = new ArrayList<>();
        if (remoteRows == null) {
            return rows;
        }
        int rowCount = Math.min(remoteRows.length(), AppConfig.MAX_DATABASE_ROWS);
        for (int index = 0; index < rowCount; index++) {
            JSONObject remoteRow = remoteRows.optJSONObject(index);
            if (remoteRow != null) {
                rows.add(parseRow(remoteRow, columns));
            }
        }
        return rows;
    }

    private static DatabaseRow parseRow(JSONObject remoteRow, List<String> columns) {
        JSONObject rowData = remoteRow.optJSONObject("data");
        List<DatabaseCell> cells = new ArrayList<>();
        for (String column : columns) {
            JSONObject remoteCell = rowData == null ? null : rowData.optJSONObject(column);
            String type = remoteCell == null ? "" : remoteCell.optString("type", "");
            Object rawValue = remoteCell == null ? null : remoteCell.opt("value");
            cells.add(new DatabaseCell(column, type,
                    formatCellValue(rawValue, AppConfig.MAX_JSON_DEPTH)));
        }
        return new DatabaseRow(safeResourceId(remoteRow.optString("page_id", "")), cells);
    }

    private void appendChildren(String parentId, int depth, List<RemoteBlock> result)
            throws ApiException {
        if (depth >= AppConfig.MAX_READER_DEPTH || result.size() >= AppConfig.MAX_READER_BLOCKS) {
            return;
        }
        for (RemoteBlock block : fetchChildren(parentId)) {
            if (result.size() >= AppConfig.MAX_READER_BLOCKS) {
                return;
            }
            result.add(block.atDepth(depth));
            if (block.hasChildren && !block.isPageLink()) {
                appendChildren(block.id, depth + 1, result);
            }
        }
    }

    void createTextBlock(String pageId, String text) throws ApiException {
        HttpURLConnection connection = null;
        boolean requestMayHaveReachedServer = false;
        try {
            String validPageId = requireResourceId(pageId);
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("content", text);
            JSONArray blocks = new JSONArray();
            blocks.put(block);
            JSONObject payload = new JSONObject();
            payload.put("parent_id", validPageId);
            payload.put("blocks", blocks);

            if (BuildConfig.DEBUG && forceUnknownWriteForTest) {
                requestMayHaveReachedServer = true;
                throw new IOException("Simulated unknown write outcome");
            }

            connection = open("POST", "/blocks");
            connection.setDoOutput(true);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                requestMayHaveReachedServer = true;
                output.write(bytes);
                output.flush();
            }
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw apiError(status, body, status >= 500);
            }
        } catch (JSONException error) {
            throw new ApiException("无法生成写入内容", error);
        } catch (IOException error) {
            throw new ApiException("写入结果无法确认", error, 0,
                    requestMayHaveReachedServer);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        if (BuildConfig.DEBUG && forceOfflineForTest) {
            throw new IOException("Simulated offline network");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(AppConfig.API_BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(16000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", token);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent",
                "X3WolaiNotes/" + BuildConfig.VERSION_NAME + " Android");
        return connection;
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = stream.read(buffer)) > 0) {
                int remaining = MAX_RESPONSE_BYTES - output.size();
                if (count > remaining) {
                    throw new IOException("Wolai response exceeds safety limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private static ApiException apiError(int status, String body) {
        return apiError(status, body, false);
    }

    private static ApiException apiError(int status, String body, boolean outcomeUnknown) {
        String message = "Wolai 返回 " + status;
        try {
            String remote = new JSONObject(body).optString("message", "").trim();
            if (!remote.isEmpty()) {
                message = remote;
            }
        } catch (JSONException ignored) {
            // Keep the status-only error; never expose a full HTML error body.
        }
        return new ApiException(message, null, status, outcomeUnknown);
    }

    private static String extractText(Object content) {
        if (content instanceof String) {
            return ((String) content).trim();
        }
        if (content instanceof JSONObject) {
            return ((JSONObject) content).optString("title", "").trim();
        }
        if (!(content instanceof JSONArray)) {
            return "";
        }
        JSONArray items = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < items.length(); index++) {
            Object item = items.opt(index);
            String fragment = "";
            if (item instanceof JSONObject) {
                fragment = ((JSONObject) item).optString("title", "");
            } else if (item instanceof String) {
                fragment = (String) item;
            }
            if (fragment.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(fragment);
        }
        return text.toString().trim();
    }

    private static String extractPageReference(Object content) {
        return extractPageReference(content, AppConfig.MAX_JSON_DEPTH);
    }

    private static String extractPageReference(Object content, int remainingDepth) {
        if (remainingDepth <= 0) {
            return "";
        }
        if (content instanceof JSONObject) {
            JSONObject item = (JSONObject) content;
            if ("bi_link".equals(item.optString("type"))) {
                String blockId = item.optString("block_id", "").trim();
                if (!blockId.isEmpty()) {
                    return blockId;
                }
                String refId = item.optString("ref_id", "").trim();
                if (!refId.isEmpty()) {
                    return refId;
                }
            }
            return "";
        }
        if (!(content instanceof JSONArray)) {
            return "";
        }
        JSONArray items = (JSONArray) content;
        for (int index = 0; index < items.length(); index++) {
            String refId = extractPageReference(items.opt(index), remainingDepth - 1);
            if (!refId.isEmpty()) {
                return refId;
            }
        }
        return "";
    }

    private static boolean hasChildren(JSONObject block) {
        if (block.optBoolean("has_children", false)) {
            return true;
        }
        Object children = block.opt("children");
        if (children instanceof JSONArray) {
            return ((JSONArray) children).length() > 0;
        }
        if (!(children instanceof JSONObject)) {
            return false;
        }
        Object ids = ((JSONObject) children).opt("ids");
        return ids instanceof JSONArray && ((JSONArray) ids).length() > 0;
    }

    private static String formatCellValue(Object value, int remainingDepth) {
        if (remainingDepth <= 0) {
            return "";
        }
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof String) {
            return ApiInputPolicy.boundedText((String) value, AppConfig.MAX_CELL_TEXT_LENGTH);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof JSONArray) {
            return formatArrayValue((JSONArray) value, remainingDepth - 1);
        }
        if (value instanceof JSONObject) {
            return formatObjectValue((JSONObject) value, remainingDepth - 1);
        }
        return ApiInputPolicy.boundedText(String.valueOf(value), AppConfig.MAX_CELL_TEXT_LENGTH);
    }

    private static String formatArrayValue(JSONArray array, int remainingDepth) {
        StringBuilder result = new StringBuilder();
        int itemCount = Math.min(array.length(), AppConfig.MAX_DATABASE_COLUMNS);
        for (int index = 0; index < itemCount; index++) {
            appendCellPart(result, formatCellValue(array.opt(index), remainingDepth));
            if (result.length() >= AppConfig.MAX_CELL_TEXT_LENGTH) {
                break;
            }
        }
        return ApiInputPolicy.boundedText(result.toString(), AppConfig.MAX_CELL_TEXT_LENGTH);
    }

    private static String formatObjectValue(JSONObject object, int remainingDepth) {
        for (String key : new String[]{"title", "name", "text", "value"}) {
            String preferred = formatCellValue(object.opt(key), remainingDepth);
            if (!preferred.isEmpty()) {
                return preferred;
            }
        }
        JSONArray names = object.names();
        if (names == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int itemCount = Math.min(names.length(), AppConfig.MAX_DATABASE_COLUMNS);
        for (int index = 0; index < itemCount; index++) {
            appendCellPart(result,
                    formatCellValue(object.opt(names.optString(index)), remainingDepth));
            if (result.length() >= AppConfig.MAX_CELL_TEXT_LENGTH) {
                break;
            }
        }
        return ApiInputPolicy.boundedText(result.toString(), AppConfig.MAX_CELL_TEXT_LENGTH);
    }

    private static void appendCellPart(StringBuilder result, String item) {
        if (item.isEmpty()) {
            return;
        }
        if (result.length() > 0) {
            result.append("、");
        }
        result.append(item);
    }

    private static long epochMillis(long value) {
        if (value <= 0L) {
            return 0L;
        }
        return value < 100_000_000_000L ? value * 1000L : value;
    }

    private static String requireResourceId(String value) throws ApiException {
        if (!ApiInputPolicy.isValidResourceId(value)) {
            throw new ApiException("Wolai 资源标识无效");
        }
        return value;
    }

    private static String safeResourceId(String value) {
        return ApiInputPolicy.isValidResourceId(value) ? value : "";
    }
}
