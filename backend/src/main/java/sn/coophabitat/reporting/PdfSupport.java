package sn.coophabitat.reporting;

import org.openpdf.text.*;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import sn.coophabitat.admin.CoopSettings;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Mise en page commune des documents PDF (A4, en-tête de la coopérative, pied de page avec référence).
 * Aucun document produit ici n'est signé électroniquement ; le pied de page le rappelle.
 */
public final class PdfSupport {

    public static final Color NAVY = new Color(0x14, 0x2B, 0x4C);
    public static final Color GREEN = new Color(0x1F, 0x5E, 0x4A);
    public static final Color GREY = new Color(0x5F, 0x67, 0x73);
    public static final Color LIGHT = new Color(0xF2, 0xF4, 0xF6);
    public static final Color RED = new Color(0xA3, 0x1D, 0x1D);
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRENCH)
            .withZone(ZoneId.of("Africa/Dakar"));

    private static final BaseFont REGULAR;
    private static final BaseFont BOLD;

    static {
        try {
            REGULAR = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BOLD = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PdfSupport() {
    }

    public static Font font(float size) {
        return new Font(REGULAR, size, Font.NORMAL, Color.BLACK);
    }

    public static Font font(float size, Color color) {
        return new Font(REGULAR, size, Font.NORMAL, color);
    }

    public static Font bold(float size) {
        return new Font(BOLD, size, Font.NORMAL, Color.BLACK);
    }

    public static Font bold(float size, Color color) {
        return new Font(BOLD, size, Font.NORMAL, color);
    }

    public static String formatDate(Instant instant) {
        return instant == null ? "—" : DATE_TIME.format(instant);
    }

    /** Construit un PDF A4 avec en-tête et pied de page standard. */
    public static byte[] build(CoopSettings coop, String title, String reference, Instant generatedAt,
                               Consumer<Document> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 64);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer(reference, generatedAt));
        doc.addTitle(title);
        doc.addCreator("CoopHabitat");
        doc.addSubject(reference);
        doc.open();
        header(doc, coop, title);
        body.accept(doc);
        doc.close();
        return out.toByteArray();
    }

    private static void header(Document doc, CoopSettings coop, String title) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(GREEN);
        c.setBorderWidth(1.5f);
        c.setPaddingBottom(8);
        c.addElement(new Paragraph(coop.getName(), bold(14, NAVY)));
        StringBuilder sub = new StringBuilder();
        append(sub, coop.getLegalForm());
        append(sub, coop.getRegistrationNumber() == null ? null : "N° d'immatriculation : " + coop.getRegistrationNumber());
        append(sub, join(coop.getAddress(), coop.getCity()));
        append(sub, join(coop.getPhone(), coop.getEmail()));
        if (!sub.isEmpty()) {
            c.addElement(new Paragraph(sub.toString(), font(8.5f, GREY)));
        }
        t.addCell(c);
        doc.add(t);
        Paragraph p = new Paragraph(title, bold(15, NAVY));
        p.setSpacingBefore(14);
        p.setSpacingAfter(10);
        doc.add(p);
    }

    public static void simulationBanner(Document doc) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase("SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ", bold(12, Color.WHITE)));
        c.setBackgroundColor(RED);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(8);
        c.setBorder(Rectangle.NO_BORDER);
        t.addCell(c);
        t.setSpacingAfter(10);
        doc.add(t);
    }

    /** Tableau clé / valeur à deux colonnes. */
    public static PdfPTable keyValueTable() {
        PdfPTable t = new PdfPTable(new float[]{35, 65});
        t.setWidthPercentage(100);
        t.setSpacingAfter(10);
        return t;
    }

    public static void row(PdfPTable t, String key, String value) {
        PdfPCell k = new PdfPCell(new Phrase(key, bold(9.5f, GREY)));
        k.setBackgroundColor(LIGHT);
        k.setPadding(6);
        k.setBorderColor(LIGHT);
        PdfPCell v = new PdfPCell(new Phrase(value == null || value.isBlank() ? "—" : value, font(10)));
        v.setPadding(6);
        v.setBorderColor(LIGHT);
        t.addCell(k);
        t.addCell(v);
    }

    public static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingAfter(10);
        t.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, bold(9, Color.WHITE)));
            c.setBackgroundColor(NAVY);
            c.setPadding(5);
            t.addCell(c);
        }
        return t;
    }

    public static void cell(PdfPTable t, String value) {
        cell(t, value, Element.ALIGN_LEFT);
    }

    public static void cell(PdfPTable t, String value, int align) {
        PdfPCell c = new PdfPCell(new Phrase(value == null ? "—" : value, font(9)));
        c.setPadding(5);
        c.setHorizontalAlignment(align);
        c.setBorderColor(new Color(0xD9, 0xDE, 0xE3));
        t.addCell(c);
    }

    public static void section(Document doc, String title) {
        Paragraph p = new Paragraph(title, bold(11.5f, GREEN));
        p.setSpacingBefore(8);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    public static void paragraph(Document doc, String text) {
        Paragraph p = new Paragraph(text, font(9.5f));
        p.setSpacingAfter(6);
        doc.add(p);
    }

    public static void note(Document doc, String text) {
        Paragraph p = new Paragraph(text, font(8, GREY));
        p.setSpacingBefore(6);
        doc.add(p);
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("  ·  ");
            }
            sb.append(s);
        }
    }

    private static String join(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.isBlank()) {
            sb.append(a);
        }
        if (b != null && !b.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(b);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String reference;
        private final Instant generatedAt;

        Footer(String reference, Instant generatedAt) {
            this.reference = reference;
            this.generatedAt = generatedAt;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            String left = "Réf. " + reference + " — généré le " + formatDate(generatedAt)
                    + " — document non signé électroniquement";
            String right = "Page " + writer.getPageNumber();
            cb.beginText();
            cb.setFontAndSize(REGULAR, 7.5f);
            cb.setColorFill(GREY);
            cb.showTextAligned(Element.ALIGN_LEFT, left, document.left(), 30, 0);
            cb.showTextAligned(Element.ALIGN_RIGHT, right, document.right(), 30, 0);
            cb.endText();
        }
    }
}
