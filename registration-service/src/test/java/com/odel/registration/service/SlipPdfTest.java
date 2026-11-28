package com.odel.registration.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlipPdfTest {
    static SlipPdf.Data data(byte[] crest) {
        return new SlipPdf.Data("Wesley University", "Directorate of Distance & E-learning", crest, "#1A2368",
                "Course registration slip · 2026/2027 first semester",
                List.of(new String[]{"Student", "Adeola Okafor"}, new String[]{"Matric no.", "WU/ODL/24/0153"}, new String[]{"Slip no.", "REG-26-1-0153"}),
                List.of(new SlipPdf.Line("CSC 201", "Data Structures and Algorithms", 3, "Core"), new SlipPdf.Line("CSC 103", "Introduction to Programming", 3, "Carry-over"),
                        new SlipPdf.Line("CSC 209", "Web Technologies", 2, "Elective")),
                8, "Verify this slip at wesley.odel.ng/verify · code R7K2-9X1M · generated 4 Feb 2027");
    }

    @Test
    void rendersTheSlip() throws Exception {
        byte[] pdf = SlipPdf.render(data(null));
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("Wesley University").contains("Course registration slip").contains("Adeola Okafor").contains("REG-26-1-0153")
                .contains("CSC 103").contains("Carry-over").contains("Total").contains("R7K2-9X1M");
    }

    @Test
    void rendersWithACrestAndSurvivesABadOne() throws Exception {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        assertThat(new PdfReader(SlipPdf.render(data(png.toByteArray()))).getNumberOfPages()).isEqualTo(1);
        assertThat(new PdfReader(SlipPdf.render(data(new byte[]{1, 2, 3}))).getNumberOfPages()).isEqualTo(1);
    }

    @Test
    void slipNumbers() {
        assertThat(RegistrationService.slipBase("2026/2027", 1, "WU/ODL/24/0153")).isEqualTo("REG-26-1-0153");
        assertThat(RegistrationService.slipBase("2026/2027", 2, "CSC-2024-77")).isEqualTo("REG-26-2-77");
        assertThat(SlipPdf.initials("Wesley University")).isEqualTo("WU");
    }
}
