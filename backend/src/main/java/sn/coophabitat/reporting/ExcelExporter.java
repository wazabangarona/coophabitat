package sn.coophabitat.reporting;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Export Excel (XLSX) générique : une feuille par tableau, en-têtes en gras, montants en nombres entiers. */
public final class ExcelExporter {

    private ExcelExporter() {
    }

    public record Sheet(String name, List<String> notes, List<String> headers, List<List<Object>> rows) {
    }

    public static byte[] export(List<Sheet> sheets) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
            CellStyle note = wb.createCellStyle();
            Font italic = wb.createFont();
            italic.setItalic(true);
            note.setFont(italic);
            for (Sheet s : sheets) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(safeName(s.name()));
                int r = 0;
                for (String n : s.notes()) {
                    Cell c = sheet.createRow(r++).createCell(0);
                    c.setCellValue(n);
                    c.setCellStyle(note);
                }
                if (!s.notes().isEmpty()) {
                    r++;
                }
                Row h = sheet.createRow(r++);
                for (int i = 0; i < s.headers().size(); i++) {
                    Cell c = h.createCell(i);
                    c.setCellValue(s.headers().get(i));
                    c.setCellStyle(header);
                }
                for (List<Object> row : s.rows()) {
                    Row x = sheet.createRow(r++);
                    for (int i = 0; i < row.size(); i++) {
                        Object v = row.get(i);
                        Cell c = x.createCell(i);
                        if (v instanceof BigDecimal b) {
                            c.setCellValue(b.doubleValue());
                            c.setCellStyle(money);
                        } else if (v instanceof Number n) {
                            c.setCellValue(n.doubleValue());
                        } else if (v instanceof LocalDate d) {
                            c.setCellValue(d);
                            c.setCellStyle(date);
                        } else if (v instanceof Boolean b) {
                            c.setCellValue(b ? "Oui" : "Non");
                        } else {
                            c.setCellValue(v == null ? "" : sanitize(v.toString()));
                        }
                    }
                }
                for (int i = 0; i < s.headers().size(); i++) {
                    sheet.setColumnWidth(i, 18 * 256);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Neutralise l'injection de formules (CSV/Excel injection). */
    static String sanitize(String s) {
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return s;
    }

    private static String safeName(String name) {
        String n = name.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return n.length() > 31 ? n.substring(0, 31) : n;
    }
}
