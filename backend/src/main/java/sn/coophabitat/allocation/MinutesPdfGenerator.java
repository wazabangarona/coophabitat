package sn.coophabitat.allocation;

import org.openpdf.text.Element;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;
import sn.coophabitat.admin.CoopSettings;
import sn.coophabitat.reporting.PdfSupport;

import java.time.Instant;
import java.util.List;

/** Procès-verbal d'attribution (PDF A4). Les zones de signature sont à compléter à la main : aucune signature n'est simulée. */
@Component
public class MinutesPdfGenerator {

    public record Person(String name, String role) {
    }

    public record CandidateLine(String memberNumber, String name, String eligibility, String reason) {
    }

    public record ResultLine(Integer rank, String memberNumber, String name, String lot, String status, String justification) {
    }

    public record Data(CoopSettings coop, AllocationCampaign campaign, int versionNo, Instant generatedAt,
                       List<Person> officials, List<String> lots, List<CandidateLine> candidates,
                       List<ResultLine> results, List<String> approvals, List<String> contests) {
    }

    public byte[] generate(Data d) {
        AllocationCampaign c = d.campaign();
        String ref = c.getReference() + "-PV" + d.versionNo();
        return PdfSupport.build(d.coop(), "PROCÈS-VERBAL D'ATTRIBUTION — " + c.getReference(), ref, d.generatedAt(), doc -> {
            if (c.isDemo()) {
                PdfSupport.paragraph(doc, "DOCUMENT DE DÉMONSTRATION — données fictives");
            }
            PdfPTable t = PdfSupport.keyValueTable();
            PdfSupport.row(t, "Procédure", c.getReference() + " — " + c.getTitle());
            PdfSupport.row(t, "Version du PV", "v" + d.versionNo() + " (projet soumis à validation)");
            PdfSupport.row(t, "Séance", (c.getSessionDate() == null ? "Date non renseignée" : PdfSupport.formatDate(c.getSessionDate()))
                    + (c.getSessionLocation() == null ? "" : " — " + c.getSessionLocation()));
            PdfSupport.row(t, "Méthode", c.getMethod() == AllocationMethod.TIRAGE_AU_SORT
                    ? "Tirage au sort vérifiable (" + c.getAlgorithmVersion() + ")" : "Décision de commission motivée");
            PdfSupport.row(t, "Date de référence", c.getReferenceDate() == null ? null : PdfSupport.DATE.format(c.getReferenceDate()));
            PdfSupport.row(t, "Exécution", PdfSupport.formatDate(c.getExecutedAt()));
            doc.add(t);

            PdfSupport.section(doc, "Responsables et membres de la commission");
            PdfPTable o = PdfSupport.table(new float[]{60, 40}, "Nom", "Qualité");
            for (Person p : d.officials()) {
                PdfSupport.cell(o, p.name());
                PdfSupport.cell(o, p.role());
            }
            doc.add(o);

            PdfSupport.section(doc, "Critères applicables (publiés le " + PdfSupport.formatDate(c.getCriteriaPublishedAt()) + ")");
            PdfSupport.paragraph(doc, c.getEligibilityText() == null ? "—" : c.getEligibilityText());
            PdfSupport.section(doc, "Règles de sélection");
            PdfSupport.paragraph(doc, c.getRulesText() == null ? "—" : c.getRulesText());

            PdfSupport.section(doc, "Lots concernés");
            PdfSupport.paragraph(doc, String.join(", ", d.lots()));

            PdfSupport.section(doc, "Liste de référence des candidats");
            PdfPTable cand = PdfSupport.table(new float[]{18, 30, 17, 35}, "N° adhérent", "Nom", "Éligibilité", "Motif");
            for (CandidateLine l : d.candidates()) {
                PdfSupport.cell(cand, l.memberNumber());
                PdfSupport.cell(cand, l.name());
                PdfSupport.cell(cand, l.eligibility());
                PdfSupport.cell(cand, l.reason());
            }
            doc.add(cand);

            PdfSupport.section(doc, "Résultats");
            PdfPTable r = PdfSupport.table(new float[]{8, 16, 26, 16, 14, 20}, "Rang", "N° adhérent", "Nom", "Lot", "État", "Justification");
            for (ResultLine l : d.results()) {
                PdfSupport.cell(r, l.rank() == null ? "—" : String.valueOf(l.rank()), Element.ALIGN_CENTER);
                PdfSupport.cell(r, l.memberNumber());
                PdfSupport.cell(r, l.name());
                PdfSupport.cell(r, l.lot());
                PdfSupport.cell(r, l.status());
                PdfSupport.cell(r, l.justification());
            }
            doc.add(r);

            PdfSupport.section(doc, "Validations enregistrées");
            PdfSupport.paragraph(doc, d.approvals().isEmpty() ? "Aucune" : String.join("\n", d.approvals()));
            PdfSupport.section(doc, "Réserves et contestations");
            PdfSupport.paragraph(doc, d.contests().isEmpty() ? "Aucune à la date de génération" : String.join("\n", d.contests()));

            PdfSupport.section(doc, "Références d'audit");
            PdfPTable a = PdfSupport.keyValueTable();
            PdfSupport.row(a, "Empreinte des participants", c.getParticipantsDigest());
            PdfSupport.row(a, "Empreinte des lots", c.getLotsDigest());
            PdfSupport.row(a, "Engagement préalable (graine)", c.getSeedCommitment());
            PdfSupport.row(a, "Graine révélée", c.isSeedRevealed() ? c.getSeedSecret() : "non révélée");
            PdfSupport.row(a, "Entropie publique", c.getPublicEntropy());
            doc.add(a);
            PdfSupport.note(doc, "Ces empreintes permettent à un auditeur de recalculer le tirage. Elles attestent la "
                    + "reproductibilité technique de la procédure, non sa régularité juridique, qui relève des statuts "
                    + "et des organes de la coopérative.");

            PdfSupport.section(doc, "Signatures");
            PdfPTable s = PdfSupport.table(new float[]{40, 30, 30}, "Nom et qualité", "Date", "Signature manuscrite");
            for (Person p : d.officials()) {
                PdfSupport.cell(s, p.name() + " — " + p.role());
                PdfSupport.cell(s, "\n\n");
                PdfSupport.cell(s, "\n\n");
            }
            doc.add(s);
            PdfSupport.note(doc, "Ce procès-verbal n'est pas signé électroniquement. Sa validation dans CoopHabitat "
                    + "est une validation interne horodatée ; elle ne remplace pas la signature des responsables.");
        });
    }
}
