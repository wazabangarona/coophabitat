package sn.coophabitat.receipt;

import org.openpdf.text.Element;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;
import sn.coophabitat.admin.CoopSettings;
import sn.coophabitat.common.AmountInWords;
import sn.coophabitat.common.Money;
import sn.coophabitat.reporting.PdfSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Reçu PDF A4, généré exclusivement à partir des données persistées. */
@Component
public class ReceiptPdfGenerator {

    public record Line(String label, BigDecimal amount) {
    }

    public record Data(CoopSettings coop, String receiptNumber, String verificationCode, String verificationUrl,
                       Instant issuedAt, Instant paidAt, String memberName, String memberNumber, BigDecimal amount,
                       String paymentMethod, String providerReference, String transactionReference,
                       String transactionState, String confirmationSource, List<Line> allocations,
                       BigDecimal advance, BigDecimal outstandingAfter, boolean simulated) {
    }

    public byte[] generate(Data d) {
        return PdfSupport.build(d.coop(), "REÇU DE PAIEMENT N° " + d.receiptNumber(), d.receiptNumber(), d.issuedAt(), doc -> {
            if (d.simulated()) {
                PdfSupport.simulationBanner(doc);
            }
            PdfPTable t = PdfSupport.keyValueTable();
            PdfSupport.row(t, "Numéro du reçu", d.receiptNumber());
            PdfSupport.row(t, "Date d'émission", PdfSupport.formatDate(d.issuedAt()));
            PdfSupport.row(t, "Date du paiement", PdfSupport.formatDate(d.paidAt()));
            PdfSupport.row(t, "Adhérent", d.memberName() + " — n° " + d.memberNumber());
            PdfSupport.row(t, "Montant", Money.format(d.amount()));
            PdfSupport.row(t, "Montant en lettres", amountInWords(d.amount()));
            PdfSupport.row(t, "Moyen de paiement", d.paymentMethod());
            PdfSupport.row(t, "Référence fournisseur", d.providerReference());
            PdfSupport.row(t, "Référence de transaction", d.transactionReference());
            PdfSupport.row(t, "État de la transaction", d.transactionState() + " (" + d.confirmationSource() + ")");
            doc.add(t);

            PdfSupport.section(doc, "Imputation du paiement");
            PdfPTable a = PdfSupport.table(new float[]{70, 30}, "Motif / période", "Montant imputé");
            for (Line l : d.allocations()) {
                PdfSupport.cell(a, l.label());
                PdfSupport.cell(a, Money.format(l.amount()), Element.ALIGN_RIGHT);
            }
            if (d.advance().signum() > 0) {
                PdfSupport.cell(a, "Avance sur échéances futures");
                PdfSupport.cell(a, Money.format(d.advance()), Element.ALIGN_RIGHT);
            }
            doc.add(a);

            PdfPTable s = PdfSupport.keyValueTable();
            PdfSupport.row(s, "Solde restant dû après ce paiement", Money.format(d.outstandingAfter()));
            doc.add(s);

            PdfSupport.section(doc, "Vérification");
            PdfSupport.paragraph(doc, "Ce reçu peut être vérifié sur " + d.verificationUrl() + " avec le numéro "
                    + d.receiptNumber() + " et le code de vérification " + d.verificationCode() + ".");
            PdfSupport.note(doc, "Reçu généré automatiquement à partir des données enregistrées dans CoopHabitat. "
                    + "Il ne comporte ni signature manuscrite ni signature électronique cryptographique. "
                    + (d.simulated() ? "Paiement simulé : aucun fonds n'a été reçu par la coopérative. " : "")
                    + "En cas de remboursement ultérieur, la vérification en ligne indique l'état à jour.");
        });
    }

    static String amountInWords(BigDecimal amount) {
        try {
            return AmountInWords.toWords(amount) + " francs CFA";
        } catch (IllegalArgumentException e) {
            return "—";
        }
    }
}
