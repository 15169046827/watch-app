package com.canace.x3voiceledger.phone;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import jxl.Sheet;
import jxl.Workbook;
import jxl.read.biff.BiffException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

final class LedgerProfileImporter {
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ROWS = 50_000;

    private LedgerProfileImporter() { }

    static Result read(ContentResolver resolver, Uri uri) throws IOException {
        String name = displayName(resolver, uri);
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取所选文件");
            bytes = readLimited(input, MAX_FILE_BYTES, "文件超过 16 MB，请改用精简的导出文件");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Result result;
        if (lower.endsWith(".xlsx")) {
            result = fromRows(readXlsx(bytes));
        } else if (lower.endsWith(".xls")) {
            result = fromRows(readXls(bytes));
        } else if (lower.endsWith(".zip")) {
            result = readExportZip(bytes);
        } else if (isZip(bytes)) {
            result = isXlsx(bytes) ? fromRows(readXlsx(bytes)) : readExportZip(bytes);
        } else if (lower.endsWith(".json") || firstNonSpace(bytes) == '{' || firstNonSpace(bytes) == '[') {
            result = readJson(decodeText(bytes));
        } else {
            result = fromRows(readDelimited(decodeText(bytes)));
        }
        result.sourceName = name;
        if (result.isEmpty()) {
            throw new IOException("没有找到可识别的账户、分类、账本或标签列；请选择一木导出的账单 Excel/CSV");
        }
        return result;
    }

    static Result fromRows(List<List<String>> rows) {
        Result result = new Result();
        if (rows.isEmpty()) return result;
        int headerIndex = findHeader(rows);
        if (headerIndex < 0) return result;
        List<String> headers = rows.get(headerIndex);
        System.out.println("X3ProfileImport headers=" + headers);
        for (int r = headerIndex + 1; r < rows.size() && result.scannedRows < MAX_ROWS; r++) {
            List<String> row = rows.get(r);
            Map<String, String> record = new HashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String key = normalize(headers.get(c));
                if (!key.isEmpty()) record.put(key, c < row.size() ? row.get(c).trim() : "");
            }
            collectRecord(record, result);
            result.scannedRows++;
        }
        result.finish();
        return result;
    }

    private static Result readJson(String text) throws IOException {
        Result result = new Result();
        try {
            Object root = text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
            if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                collectArray(object, result.accountSet, "accounts", "账户");
                collectArray(object, result.bookSet, "books", "账本");
                collectTags(object.opt("availableTags"), result.tagSet);
                collectTags(object.opt("tags"), result.tagSet);
                collectJsonCategories(object.optJSONArray("categories"), result);
                Object records = object.has("records") ? object.opt("records") : object.opt("bills");
                collectJsonRecords(records, result);
            } else {
                collectJsonRecords(root, result);
            }
        } catch (JSONException error) {
            throw new IOException("JSON 文件格式无效", error);
        }
        result.finish();
        return result;
    }

    private static void collectJsonRecords(Object value, Result result) throws JSONException {
        if (!(value instanceof JSONArray)) return;
        JSONArray records = (JSONArray) value;
        for (int i = 0; i < records.length() && result.scannedRows < MAX_ROWS; i++) {
            JSONObject item = records.optJSONObject(i);
            if (item == null) continue;
            Map<String, String> record = new HashMap<>();
            JSONArray names = item.names();
            if (names != null) for (int j = 0; j < names.length(); j++) {
                String name = names.optString(j);
                record.put(normalize(name), item.optString(name));
            }
            collectRecord(record, result);
            result.scannedRows++;
        }
    }

    private static void collectJsonCategories(JSONArray values, Result result) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            String parent = firstValue(item, "name", "parent", "parentCategory", "一级分类");
            if (parent.isEmpty()) continue;
            JSONArray children = item.optJSONArray("children");
            if (children == null) result.addCategory(parent, "");
            else for (int j = 0; j < children.length(); j++) result.addCategory(parent, children.optString(j));
        }
    }

    private static void collectArray(JSONObject object, Set<String> target, String... keys) {
        for (String key : keys) {
            JSONArray values = object.optJSONArray(key);
            if (values == null) continue;
            for (int i = 0; i < values.length(); i++) add(target, values.optString(i));
        }
    }

    private static void collectRecord(Map<String, String> record, Result result) {
        add(result.accountSet, value(record, "账户", "账户名称", "资产", "asset", "account", "支付账户", "资金账户"));
        add(result.bookSet, value(record, "账本", "账本名称", "book", "bookname"));
        collectTags(value(record, "标签", "tag", "tags"), result.tagSet);
        String parent = value(record, "父分类", "父分类名称", "父级分类", "一级分类", "一级分类名称",
                "一级分类名", "大类", "大分类", "主分类", "parentcategory", "categoryparent");
        String child = value(record, "子分类", "子分类名称", "子级分类", "二级分类", "二级分类名称",
                "二级分类名", "小类", "明细分类", "childcategory", "categorychild");
        if (parent.isEmpty()) {
            String combined = value(record, "分类", "分类名称", "分类名", "账单分类", "消费分类",
                    "类别", "收支分类", "收支类别", "category");
            String[] pair = splitCategory(combined);
            parent = pair[0];
            if (child.isEmpty()) child = pair[1];
        }
        result.addCategory(parent, child);
    }

    private static int findHeader(List<List<String>> rows) {
        int limit = Math.min(rows.size(), 30);
        int best = -1;
        int bestScore = 0;
        for (int r = 0; r < limit; r++) {
            int score = 0;
            for (String cell : rows.get(r)) if (isKnownHeader(normalize(cell))) score++;
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return bestScore > 0 ? best : -1;
    }

    private static boolean isKnownHeader(String value) {
        return value(value, "账户", "账户名称", "资产", "asset", "account", "支付账户", "资金账户",
                "父分类", "父分类名称", "父级分类", "一级分类", "一级分类名称", "一级分类名", "大类",
                "大分类", "主分类", "parentcategory", "categoryparent", "子分类", "子分类名称",
                "子级分类", "二级分类", "二级分类名称", "二级分类名", "小类", "明细分类",
                "childcategory", "categorychild", "分类", "分类名称", "分类名", "账单分类", "消费分类",
                "类别", "category", "收支分类", "收支类别", "账本",
                "账本名称", "book", "bookname", "标签", "tag", "tags");
    }

    private static List<List<String>> readDelimited(String text) {
        char delimiter = detectDelimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length() && rows.size() < MAX_ROWS + 30; i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == delimiter) { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n') { row.add(trimCr(cell)); rows.add(row); row = new ArrayList<>(); cell.setLength(0); }
            else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(trimCr(cell)); rows.add(row); }
        return rows;
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int commas = count(first, ','), tabs = count(first, '\t'), semicolons = count(first, ';');
        if (tabs >= commas && tabs >= semicolons) return '\t';
        return semicolons > commas ? ';' : ',';
    }

    private static List<List<String>> readXlsx(byte[] bytes) throws IOException {
        List<String> shared = new ArrayList<>();
        Map<String, byte[]> sheets = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    shared = parseSharedStrings(readLimited(zip, MAX_ZIP_ENTRY_BYTES, "Excel 字符串表过大"));
                } else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
                    sheets.put(name, readLimited(zip, MAX_ZIP_ENTRY_BYTES, "Excel 工作表过大"));
                }
                zip.closeEntry();
            }
        }
        List<Map.Entry<String, byte[]>> ordered = new ArrayList<>(sheets.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getKey));
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, byte[]> sheet : ordered) {
            rows.addAll(parseSheet(sheet.getValue(), shared, MAX_ROWS + 30 - rows.size()));
            if (rows.size() >= MAX_ROWS + 30) break;
        }
        return rows;
    }

    private static List<List<String>> readXls(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            Workbook workbook = Workbook.getWorkbook(input);
            try {
                for (Sheet sheet : workbook.getSheets()) {
                    int rowCount = Math.min(sheet.getRows(), MAX_ROWS + 30 - rows.size());
                    int columnCount = sheet.getColumns();
                    for (int r = 0; r < rowCount; r++) {
                        List<String> row = new ArrayList<>();
                        for (int c = 0; c < columnCount; c++) row.add(sheet.getCell(c, r).getContents());
                        rows.add(row);
                    }
                    if (rows.size() >= MAX_ROWS + 30) break;
                }
            } finally {
                workbook.close();
            }
        } catch (BiffException error) {
            throw new IOException("旧式 Excel 文件无法解析", error);
        }
        return rows;
    }

    private static Result readExportZip(byte[] bytes) throws IOException {
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                boolean supported = name.endsWith(".xls") || name.endsWith(".xlsx")
                        || name.endsWith(".csv") || name.endsWith(".tsv")
                        || name.endsWith(".txt") || name.endsWith(".json");
                if (!supported) continue;
                byte[] content = readLimited(zip, MAX_ZIP_ENTRY_BYTES, "压缩包内文件超过 8 MB");
                total += content.length;
                if (total > MAX_FILE_BYTES) throw new IOException("压缩包解压内容超过 16 MB");
                if (name.endsWith(".xlsx")) return fromRows(readXlsx(content));
                if (name.endsWith(".xls")) return fromRows(readXls(content));
                if (name.endsWith(".json")) return readJson(decodeText(content));
                return fromRows(readDelimited(decodeText(content)));
            }
        }
        throw new IOException("压缩包中没有找到 XLS/XLSX、CSV、文本或 JSON 导出文件");
    }

    private static boolean isXlsx(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("xl/workbook.xml".equals(entry.getName())) return true;
            }
        }
        return false;
    }

    private static List<String> parseSharedStrings(byte[] xml) throws IOException {
        List<String> values = new ArrayList<>();
        parseXml(xml, new DefaultHandler() {
            private boolean inItem;
            private boolean inText;
            private StringBuilder value;
            @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                if ("si".equals(qName)) { inItem = true; value = new StringBuilder(); }
                if (inItem && "t".equals(qName)) inText = true;
            }
            @Override public void characters(char[] ch, int start, int length) {
                if (inText) value.append(ch, start, length);
            }
            @Override public void endElement(String uri, String local, String qName) {
                if ("t".equals(qName)) inText = false;
                if ("si".equals(qName)) { values.add(value.toString()); inItem = false; }
            }
        });
        return values;
    }

    private static List<List<String>> parseSheet(byte[] xml, List<String> shared, int limit) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        parseXml(xml, new DefaultHandler() {
            private List<String> row;
            private int column;
            private String type;
            private boolean inValue;
            private StringBuilder value;
            @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                if ("row".equals(qName)) row = new ArrayList<>();
                else if ("c".equals(qName) && row != null) {
                    type = attributes.getValue("t");
                    column = columnFromRef(attributes.getValue("r"));
                    value = new StringBuilder();
                } else if (("v".equals(qName) || "t".equals(qName)) && value != null) inValue = true;
            }
            @Override public void characters(char[] ch, int start, int length) {
                if (inValue) value.append(ch, start, length);
            }
            @Override public void endElement(String uri, String local, String qName) {
                if ("v".equals(qName) || "t".equals(qName)) inValue = false;
                else if ("c".equals(qName) && row != null) {
                    while (row.size() <= column) row.add("");
                    String cell = value.toString();
                    if ("s".equals(type)) {
                        try { int index = Integer.parseInt(cell); cell = index >= 0 && index < shared.size() ? shared.get(index) : ""; }
                        catch (NumberFormatException ignored) { cell = ""; }
                    }
                    row.set(column, cell);
                    value = null;
                } else if ("row".equals(qName) && row != null) {
                    if (rows.size() < limit) rows.add(row);
                    row = null;
                }
            }
        });
        return rows;
    }

    private static void parseXml(byte[] xml, DefaultHandler handler) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(xml)), handler);
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Excel XML 无法解析", error);
        }
    }

    private static int columnFromRef(String ref) {
        if (ref == null) return 0;
        int value = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            value = value * 26 + ch - 'A' + 1;
        }
        return Math.max(0, value - 1);
    }

    private static String decodeText(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) return new String(bytes, Charset.forName("GB18030"));
        return utf8.startsWith("\uFEFF") ? utf8.substring(1) : utf8;
    }

    private static byte[] readLimited(InputStream input, int limit, String message) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 32_768));
        byte[] buffer = new byte[8192];
        int total = 0, read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException(message);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null ? "所选文件" : segment;
    }

    private static void collectTags(Object value, Set<String> target) {
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            for (int i = 0; i < values.length(); i++) add(target, values.optString(i));
        } else if (value != null) {
            for (String tag : String.valueOf(value).split("[\\s,，;；]+")) add(target, tag);
        }
    }

    private static String firstValue(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String value(Map<String, String> record, String... keys) {
        for (String key : keys) {
            String value = record.get(normalize(key));
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static boolean value(String candidate, String... keys) {
        for (String key : keys) if (candidate.equals(normalize(key))) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("_", "").replace("-", "");
    }

    private static String[] splitCategory(String value) {
        String source = value == null ? "" : value.trim();
        for (String delimiter : new String[]{"→", ">", "｜", "|", "/", "\\"}) {
            int index = source.indexOf(delimiter);
            if (index > 0 && index < source.length() - delimiter.length()) {
                return new String[]{source.substring(0, index).trim(), source.substring(index + delimiter.length()).trim()};
            }
        }
        return new String[]{source, ""};
    }

    private static void add(Set<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) target.add(value.trim());
    }

    private static int count(String value, char ch) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == ch) result++;
        return result;
    }

    private static String trimCr(StringBuilder value) {
        int length = value.length();
        if (length > 0 && value.charAt(length - 1) == '\r') return value.substring(0, length - 1);
        return value.toString();
    }

    private static boolean isZip(byte[] value) {
        return value.length >= 4 && value[0] == 'P' && value[1] == 'K';
    }

    private static char firstNonSpace(byte[] value) {
        String text = decodeText(value);
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) return text.charAt(i);
        return 0;
    }

    static final class Result {
        String sourceName = "";
        int scannedRows;
        final Set<String> accountSet = new LinkedHashSet<>();
        final Set<String> bookSet = new LinkedHashSet<>();
        final Set<String> tagSet = new LinkedHashSet<>();
        final Map<String, LinkedHashSet<String>> categorySet = new LinkedHashMap<>();
        List<String> accounts = Collections.emptyList();
        List<String> books = Collections.emptyList();
        List<String> tags = Collections.emptyList();
        List<YimuCategoryMapper.Parent> categories = Collections.emptyList();

        void addCategory(String parent, String child) {
            if (parent == null || parent.trim().isEmpty()) return;
            LinkedHashSet<String> children = categorySet.get(parent.trim());
            if (children == null) {
                children = new LinkedHashSet<>();
                categorySet.put(parent.trim(), children);
            }
            if (child != null && !child.trim().isEmpty()) children.add(child.trim());
        }

        void finish() {
            accounts = limited(accountSet, 50);
            books = limited(bookSet, 30);
            tags = limited(tagSet, 100);
            List<YimuCategoryMapper.Parent> values = new ArrayList<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : categorySet.entrySet()) {
                if (values.size() >= 50) break;
                values.add(new YimuCategoryMapper.Parent(shortName(entry.getKey()),
                        limited(entry.getValue(), 20)));
            }
            categories = values;
        }

        boolean isEmpty() {
            return accounts.isEmpty() && books.isEmpty() && tags.isEmpty() && categories.isEmpty();
        }

        String preview() {
            return "文件：" + sourceName + "\n已扫描 " + scannedRows + " 行（不会导入历史账单）\n\n"
                    + "账户 " + accounts.size() + " 个" + sample(accounts)
                    + "\n分类 " + categories.size() + " 组" + sampleParents(categories)
                    + "\n账本 " + books.size() + " 个" + sample(books)
                    + "\n标签 " + tags.size() + " 个" + sample(tags)
                    + "\n\n将与现有配置合并，不会覆盖当前默认项。";
        }

        String categoryDetails() {
            if (categories.isEmpty()) return "未从导出文件中发现分类。";
            StringBuilder text = new StringBuilder();
            for (YimuCategoryMapper.Parent parent : categories) {
                if (text.length() > 0) text.append("\n\n");
                text.append("【").append(parent.name).append("】");
                if (parent.children.isEmpty()) text.append("\n  （没有二级分类）");
                else for (String child : parent.children) text.append("\n  · ").append(child);
            }
            return text.toString();
        }

        private static String sample(List<String> values) {
            if (values.isEmpty()) return "";
            return "：" + String.join("、", values.subList(0, Math.min(4, values.size())))
                    + (values.size() > 4 ? "…" : "");
        }

        private static String sampleParents(List<YimuCategoryMapper.Parent> values) {
            List<String> names = new ArrayList<>();
            for (YimuCategoryMapper.Parent parent : values) names.add(parent.name);
            return sample(names);
        }

        private static List<String> limited(Set<String> source, int limit) {
            List<String> values = new ArrayList<>();
            for (String value : source) {
                if (values.size() >= limit) break;
                String normalized = shortName(value);
                if (!normalized.isEmpty() && !values.contains(normalized)) values.add(normalized);
            }
            return values;
        }

        private static String shortName(String value) {
            String trimmed = value == null ? "" : value.trim();
            return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
        }
    }
}
