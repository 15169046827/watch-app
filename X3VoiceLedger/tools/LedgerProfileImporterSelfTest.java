package com.canace.x3voiceledger.phone;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jxl.Workbook;
import jxl.write.Label;
import jxl.write.WritableSheet;
import jxl.write.WritableWorkbook;

public final class LedgerProfileImporterSelfTest {
    public static void main(String[] args) throws Exception {
        testRows();
        testXlsx();
        testXls();
        System.out.println("LedgerProfileImporterSelfTest: all cases passed");
    }

    private static void testRows() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("一木记账导出"),
                Arrays.asList("时间", "账户", "一级分类", "二级分类", "账本", "标签", "金额"),
                Arrays.asList("2026-08-01", "支付宝", "食品餐饮", "晚餐", "日常", "外食 工作", "38.76"),
                Arrays.asList("2026-08-02", "微信", "收入", "工资", "主账本", "工资", "5000"),
                Arrays.asList("2026-08-03", "支付宝", "食品餐饮", "午餐", "日常", "外食", "25"));
        LedgerProfileImporter.Result result = LedgerProfileImporter.fromRows(rows);
        expect(result.accounts.size() == 2, "accounts");
        expect(result.books.size() == 2, "books");
        expect(result.tags.size() == 3, "tags");
        expect(result.categories.size() == 2, "categories");
        expect(result.categories.get(0).children.size() == 2, "category children");
    }

    @SuppressWarnings("unchecked")
    private static void testXlsx() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet><sheetData>"
                + row(1, "账户", "一级分类", "二级分类", "账本", "标签")
                + row(2, "支付宝", "食品餐饮", "晚餐", "日常", "外食")
                + "</sheetData></worksheet>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Method readXlsx = LedgerProfileImporter.class.getDeclaredMethod("readXlsx", byte[].class);
        readXlsx.setAccessible(true);
        LedgerProfileImporter.Result result = LedgerProfileImporter.fromRows(
                (List<List<String>>) readXlsx.invoke(null, bytes.toByteArray()));
        expect(result.accounts.size() == 1, "xlsx account");
        expect(result.categories.size() == 1, "xlsx category");
        expect("晚餐".equals(result.categories.get(0).children.get(0)), "xlsx child");
    }

    private static String row(int number, String... values) {
        StringBuilder result = new StringBuilder("<row r=\"").append(number).append("\">");
        for (int i = 0; i < values.length; i++) {
            result.append("<c r=\"").append((char) ('A' + i)).append(number)
                    .append("\" t=\"inlineStr\"><is><t>").append(values[i])
                    .append("</t></is></c>");
        }
        return result.append("</row>").toString();
    }

    private static void testXls() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableWorkbook workbook = Workbook.createWorkbook(bytes);
        WritableSheet sheet = workbook.createSheet("账单", 0);
        String[][] cells = {
                {"账户", "类别", "二级分类", "账本", "标签"},
                {"支付宝", "食品餐饮", "晚餐", "日常", "外食"}
        };
        for (int r = 0; r < cells.length; r++) {
            for (int c = 0; c < cells[r].length; c++) sheet.addCell(new Label(c, r, cells[r][c]));
        }
        workbook.write();
        workbook.close();
        Method readXls = LedgerProfileImporter.class.getDeclaredMethod("readXls", byte[].class);
        readXls.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) readXls.invoke(null, bytes.toByteArray());
        LedgerProfileImporter.Result result = LedgerProfileImporter.fromRows(rows);
        expect(result.accounts.size() == 1, "xls account");
        expect(result.categories.size() == 1, "xls category");
    }

    private static void expect(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
