package com.odel.payment.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptPdfTest {
    static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void rendersEveryLine() throws Exception {
        var rows = ReceiptPdf.rows();
        rows.put("Receipt no.", "WU-PF-882134");
        rows.put("Student", "Adeola Okafor");
        rows.put("Matric no.", "WU/ODL/24/0153");
        rows.put("Session", "2026/2027");
        rows.put("Semester", "1st semester");
        rows.put("Amount", Money.ngn(1_500_000));
        rows.put("Method", "Debit card · GTBank card ending 4412");
        rows.put("Paystack reference", "4099260516");
        for (byte[] crest : new byte[][]{png(), null, "not an image".getBytes(StandardCharsets.UTF_8)}) {
            byte[] pdf = ReceiptPdf.render(new ReceiptPdf.Data("Wesley University", "Wesley ODeL", "#1A2368", crest,
                    "Platform fee receipt · NGN 15,000.00", rows, "Check code 7Q2K."));
            assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
            assertThat(text).contains("Wesley University", "Platform fee receipt", "WU-PF-882134", "Adeola Okafor", "WU/ODL/24/0153",
                    "2026/2027", "1st semester", "NGN 15,000.00", "4412", "4099260516", "Check code 7Q2K");
        }
    }
}
