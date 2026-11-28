package com.odel.registration.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The course registration slip (R-05) as an A4 PDF: crest and university, title with session and semester, the student's
 * details, the courses with their units and type, the total, and the verification line at the foot.
 */
public final class SlipPdf {
    private SlipPdf() {}

    public record Line(String code, String title, int units, String type) {}

    public record Data(String universityName, String directorate, byte[] crest, String primaryColor, String title,
                       List<String[]> details, List<Line> courses, int totalUnits, String footer) {}

    public static byte[] render(Data d) {
        Color brand = color(d.primaryColor());
        Color grey = new Color(0x55, 0x5B, 0x6B);
        Color line = new Color(0xE2, 0xE4, 0xEA);
        Font uni = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(0x1D, 0x1D, 0x24));
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, grey);
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(0x1D, 0x1D, 0x24));
        Font label = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, grey);
        Font value = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, new Color(0x1D, 0x1D, 0x24));
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, grey);
        Font cell = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, new Color(0x1D, 0x1D, 0x24));
        Font cellBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, new Color(0x1D, 0x1D, 0x24));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 54, 54, 48, 60);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document document) {
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_LEFT, new Phrase(d.footer(), small),
                        document.left(), document.bottom() - 24, 0);
            }
        });
        doc.addTitle(d.title());
        doc.addCreator(d.universityName() + " ODeL");
        doc.open();

        // Header: crest (or the initials in the university's colour), name, directorate.
        PdfPTable header = new PdfPTable(new float[]{1f, 9f});
        header.setWidthPercentage(100);
        PdfPCell crest;
        Image img = null;
        if (d.crest() != null) {
            try {
                img = Image.getInstance(d.crest());
                img.scaleToFit(42, 42);
            } catch (Exception ignored) {
                img = null;
            }
        }
        if (img != null) {
            crest = new PdfPCell(img, false);
        } else {
            Font initials = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.WHITE);
            crest = new PdfPCell(new Phrase(initials(d.universityName()), initials));
            crest.setBackgroundColor(brand);
            crest.setHorizontalAlignment(Element.ALIGN_CENTER);
            crest.setFixedHeight(42);
        }
        crest.setBorder(Rectangle.NO_BORDER);
        crest.setVerticalAlignment(Element.ALIGN_MIDDLE);
        header.addCell(crest);
        PdfPCell name = new PdfPCell();
        name.setBorder(Rectangle.NO_BORDER);
        name.setPaddingLeft(10);
        name.setVerticalAlignment(Element.ALIGN_MIDDLE);
        name.addElement(new Paragraph(d.universityName(), uni));
        name.addElement(new Paragraph(d.directorate(), small));
        header.addCell(name);
        doc.add(header);

        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingBefore(10);
        PdfPCell r = new PdfPCell();
        r.setBorder(Rectangle.BOTTOM);
        r.setBorderColorBottom(brand);
        r.setBorderWidthBottom(1.5f);
        r.setFixedHeight(2);
        rule.addCell(r);
        doc.add(rule);

        Paragraph t = new Paragraph(d.title(), title);
        t.setSpacingBefore(14);
        t.setSpacingAfter(10);
        doc.add(t);

        PdfPTable details = new PdfPTable(new float[]{2.2f, 7.8f});
        details.setWidthPercentage(100);
        for (String[] row : d.details()) {
            PdfPCell a = new PdfPCell(new Phrase(row[0], label));
            PdfPCell b = new PdfPCell(new Phrase(row[1], value));
            a.setBorder(Rectangle.NO_BORDER);
            b.setBorder(Rectangle.NO_BORDER);
            a.setPaddingBottom(4);
            b.setPaddingBottom(4);
            details.addCell(a);
            details.addCell(b);
        }
        doc.add(details);

        PdfPTable courses = new PdfPTable(new float[]{1.5f, 5.5f, 1f, 1.6f});
        courses.setWidthPercentage(100);
        courses.setSpacingBefore(14);
        courses.setHeaderRows(1);
        for (String h : new String[]{"Code", "Course", "Units", "Type"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, head));
            c.setBackgroundColor(new Color(0xEC, 0xED, 0xF2));
            c.setBorderColor(line);
            c.setBorder(Rectangle.BOTTOM);
            c.setPadding(6);
            courses.addCell(c);
        }
        for (Line l : d.courses()) {
            courses.addCell(cell(l.code(), cellBold, line));
            courses.addCell(cell(l.title(), cell, line));
            courses.addCell(cell(Integer.toString(l.units()), cell, line));
            courses.addCell(cell(l.type(), cell, line));
        }
        courses.addCell(cell("", cell, line));
        courses.addCell(cell("Total", cellBold, line));
        courses.addCell(cell(Integer.toString(d.totalUnits()), cellBold, line));
        courses.addCell(cell("", cell, line));
        doc.add(courses);

        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font f, Color line) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(line);
        c.setPadding(6);
        return c;
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.split("\\s+")) if (!w.isEmpty() && Character.isUpperCase(w.charAt(0)) && sb.length() < 2) sb.append(w.charAt(0));
        return sb.isEmpty() ? "U" : sb.toString();
    }

    private static Color color(String hex) {
        try {
            return Color.decode(hex == null ? "#1A2368" : hex);
        } catch (RuntimeException e) {
            return new Color(0x1A, 0x23, 0x68);
        }
    }
}
