package com.odel.common.files;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {
    @Test
    void readsQuotesCommasLineBreaksAndBom() {
        String text = "﻿code,title\r\nCSC 201,\"Data Structures, Algorithms\"\r\n\r\nGST 201,\"Say \"\"hi\"\"\nthere\"\n";
        List<List<String>> rows = Csv.parse(text.getBytes(StandardCharsets.UTF_8));
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).containsExactly("code", "title");
        List<Csv.Row> data = Csv.dataRows(rows);
        assertThat(data).hasSize(2);
        assertThat(data.get(0).cells()).containsExactly("CSC 201", "Data Structures, Algorithms");
        assertThat(data.get(1).number()).isEqualTo(4);
        assertThat(data.get(1).get(1)).isEqualTo("Say \"hi\"\nthere");
        assertThat(Csv.headerMatches(rows, List.of("code", "title"))).isTrue();
        assertThat(Csv.headerMatches(rows, List.of("code"))).isFalse();
    }

    @Test
    void writesQuotedCellsBack() {
        assertThat(Csv.line(List.of("a", "b,c", "d\"e"))).isEqualTo("a,\"b,c\",\"d\"\"e\"\r\n");
        assertThat(Csv.parse(Csv.line(List.of("a", "b,c")))).containsExactly(List.of("a", "b,c"));
    }
}
