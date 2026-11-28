package com.odel.payment.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** P-10: the platform fee receipt as an A4 PDF (OpenPDF). Standard fonts only, so amounts read "NGN 15,000.00". */
public final class ReceiptPdf {
    private ReceiptPdf() {}

    public record Data(String universityName, String subtitle, String primaryColor, byte[] crestPng, String title, Map<String, String> rows,
                       String footer) {}

    public static Map<String, String> rows() {
        return new LinkedHashMap<>();
    }

    public static byte[] render(Data d) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
            PdfWriter.getInstance(doc, out);
            doc.addTitle(d.title());
            doc.addAuthor(d.universityName());
            doc.open();
            Color brand = color(d.primaryColor());

            PdfPTable head = new PdfPTable(d.crestPng() == null ? new float[]{1} : new float[]{1, 8});
            head.setWidthPercentage(100);
            if (d.crestPng() != null) {
                try {
                    Image crest = Image.getInstance(d.crestPng());
                    crest.scaleToFit(40, 40);
                    PdfPCell c = new PdfPCell(crest, false);
                    c.setBorder(Rectangle.NO_BORDER);
                    c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    head.addCell(c);
                } catch (Exception badImage) {
                    head = new PdfPTable(1);
                    head.setWidthPercentage(100);
                }
            }
            Paragraph who = new Paragraph();
            who.add(new Chunk(d.universityName() + "\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK)));
            if (d.subtitle() != null) who.add(new Chunk(d.subtitle(), FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY)));
            PdfPCell w = new PdfPCell(who);
            w.setBorder(Rectangle.NO_BORDER);
            w.setVerticalAlignment(Element.ALIGN_MIDDLE);
            head.addCell(w);
            doc.add(head);

            LineSeparator line = new LineSeparator(1.2f, 100, brand, Element.ALIGN_CENTER, -6);
            doc.add(new Chunk(line));
            Paragraph title = new Paragraph(d.title(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.BLACK));
            title.setSpacingBefore(18);
            title.setSpacingAfter(12);
            doc.add(title);

            PdfPTable t = new PdfPTable(new float[]{2.2f, 5});
            t.setWidthPercentage(100);
            Font label = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Color.GRAY);
            Font value = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, Color.BLACK);
            for (Map.Entry<String, String> r : d.rows().entrySet()) {
                PdfPCell k = new PdfPCell(new Phrase(r.getKey(), label));
                PdfPCell v = new PdfPCell(new Phrase(r.getValue() == null ? "" : r.getValue(), value));
                for (PdfPCell c : new PdfPCell[]{k, v}) {
                    c.setBorder(Rectangle.NO_BORDER);
                    c.setPaddingBottom(5);
                    t.addCell(c);
                }
            }
            doc.add(t);

            if (d.footer() != null) {
                Paragraph f = new Paragraph(d.footer(), FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Color.GRAY));
                f.setSpacingBefore(40);
                doc.add(f);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Receipt could not be drawn", e);
        }
    }

    static Color color(String hex) {
        try {
            return Color.decode(hex == null ? "#1A2368" : hex);
        } catch (Exception e) {
            return new Color(0x1A, 0x23, 0x68);
        }
    }
}
