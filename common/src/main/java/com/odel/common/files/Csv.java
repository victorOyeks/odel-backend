package com.odel.common.files;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the CSV files people export from spreadsheets: quoted cells, commas and line breaks inside quotes,
 * CRLF or LF, a UTF-8 byte-order mark. Blank lines are skipped. Small on purpose: no library needed.
 */
public final class Csv {
    private Csv() {}

    /** One data row: its line number in the file (the header is row 1, as a spreadsheet shows it) and its cells. */
    public record Row(int number, List<String> cells) {
        public String get(int i) {
            return i < cells.size() ? cells.get(i).trim() : "";
        }
    }

    public static List<List<String>> parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        return parse(text);
    }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        // Blank lines at the end are dropped; blank lines in between are kept so row numbers match the spreadsheet.
        while (!rows.isEmpty() && blank(rows.get(rows.size() - 1))) rows.remove(rows.size() - 1);
        return rows;
    }

    /** The data rows under the header, numbered as a spreadsheet numbers them (header = 1); blank lines are skipped but still counted. */
    public static List<Row> dataRows(List<List<String>> parsed) {
        List<Row> out = new ArrayList<>();
        for (int i = 1; i < parsed.size(); i++) if (!blank(parsed.get(i))) out.add(new Row(i + 1, parsed.get(i)));
        return out;
    }

    private static boolean blank(List<String> row) {
        return row.stream().allMatch(String::isBlank);
    }

    /** True when the first row has exactly these columns (case and spaces ignored). */
    public static boolean headerMatches(List<List<String>> parsed, List<String> expected) {
        if (parsed.isEmpty()) return false;
        List<String> h = parsed.get(0).stream().map(s -> s.trim().toLowerCase().replace(' ', '_')).toList();
        List<String> trimmed = new ArrayList<>(h);
        while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).isEmpty()) trimmed.remove(trimmed.size() - 1);
        return trimmed.equals(expected);
    }

    public static String line(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            String v = cells.get(i) == null ? "" : cells.get(i);
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.append("\r\n").toString();
    }
}
