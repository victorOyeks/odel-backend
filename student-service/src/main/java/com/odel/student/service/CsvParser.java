package com.odel.student.service;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV the way spreadsheets write it (RFC 4180): commas, quoted fields with "" for a quote, line breaks inside
 * quotes, CRLF or LF, a UTF-8 byte-order mark. Blank lines are skipped. Each record keeps its line number in the file.
 */
public final class CsvParser {
    private CsvParser() {}

    public record Record(int line, List<String> fields) {
        public boolean blank() { return fields.stream().allMatch(f -> f == null || f.isBlank()); }
    }

    public static List<Record> parse(Reader in) throws IOException {
        List<Record> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, fieldStarted = false, first = true;
        int line = 1, recordLine = 1;
        int c;
        while ((c = in.read()) != -1) {
            if (first) {
                first = false;
                if (c == '﻿') continue;
            }
            if (quoted) {
                if (c == '"') {
                    in.mark(1);
                    int next = in.read();
                    if (next == '"') field.append('"');
                    else {
                        quoted = false;
                        if (next == -1) break;
                        in.reset();
                    }
                } else {
                    if (c == '\n') line++;
                    field.append((char) c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (!fieldStarted && field.isEmpty()) quoted = true; else field.append('"');
                    fieldStarted = true;
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                }
                case '\r' -> { /* CRLF: the \n ends the record */ }
                case '\n' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    Record r = new Record(recordLine, List.copyOf(fields));
                    if (!r.blank()) out.add(r);
                    fields.clear();
                    line++;
                    recordLine = line;
                }
                default -> {
                    field.append((char) c);
                    fieldStarted = true;
                }
            }
        }
        if (fieldStarted || !field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            Record r = new Record(recordLine, List.copyOf(fields));
            if (!r.blank()) out.add(r);
        }
        return out;
    }

    /** One CSV field, quoted when it needs to be. */
    public static String escape(String value) {
        if (value == null) return "";
        boolean needs = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String v = value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")
                ? "'" + value : value; // never let a spreadsheet run a formula from imported data
        return needs ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
