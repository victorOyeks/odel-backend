package com.odel.student.service;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvParserTest {
    List<CsvParser.Record> parse(String s) throws Exception {
        return CsvParser.parse(new StringReader(s));
    }

    @Test
    void readsQuotedFieldsCrlfAndABom() throws Exception {
        var r = parse("\uFEFFmatric_number,surname\r\n\"WU/ODL/24/0153\",\"Okafor, Jr\"\r\n");
        assertThat(r).hasSize(2);
        assertThat(r.get(0).fields()).containsExactly("matric_number", "surname");
        assertThat(r.get(1).fields()).containsExactly("WU/ODL/24/0153", "Okafor, Jr");
        assertThat(r.get(1).line()).isEqualTo(2);
    }

    @Test
    void doubledQuotesLineBreaksInQuotesAndBlankLines() throws Exception {
        var r = parse("a,b\n\"say \"\"hi\"\"\",\"two\nlines\"\n\n,\nlast,row");
        assertThat(r).hasSize(3);
        assertThat(r.get(1).fields()).containsExactly("say \"hi\"", "two\nlines");
        assertThat(r.get(2).fields()).containsExactly("last", "row");
        assertThat(r.get(2).line()).isEqualTo(6);
    }

    @Test
    void emptyTrailingFieldsAndNoFinalNewline() throws Exception {
        var r = parse("a,b,c\n1,,\n2,x,");
        assertThat(r.get(1).fields()).containsExactly("1", "", "");
        assertThat(r.get(2).fields()).containsExactly("2", "x", "");
    }

    @Test
    void escapesForDownloads() {
        assertThat(CsvParser.escape("plain")).isEqualTo("plain");
        assertThat(CsvParser.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvParser.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvParser.escape("=cmd()")).isEqualTo("'=cmd()");
    }
}
