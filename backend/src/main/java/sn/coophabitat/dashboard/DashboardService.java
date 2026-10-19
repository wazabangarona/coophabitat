package sn.coophabitat.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.allocation.AllocationCampaignRepository;
import sn.coophabitat.allocation.CampaignStatus;
import sn.coophabitat.assembly.GeneralAssembly;
import sn.coophabitat.assembly.GeneralAssemblyRepository;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.config.ClockConfig;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.dashboard.DashboardDtos.*;
import sn.coophabitat.finance.FinanceDtos.TransparencySummary;
import sn.coophabitat.finance.TransparencyService;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.notification.NotificationRepository;
import sn.coophabitat.notification.NotificationStatus;
import sn.coophabitat.payment.PaymentTransaction;
import sn.coophabitat.payment.PaymentTransactionRepository;
import sn.coophabitat.property.LotStatus;
import sn.coophabitat.property.PropertyLotRepository;
import sn.coophabitat.reporting.PdfSupport;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.Permission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Tableaux de bord du bureau : les sections affichées dépendent des droits de l'utilisateur. */
@Service
public class DashboardService {

    private static final Map<MemberStatus, String> MEMBER_LABELS = Map.of(MemberStatus.EN_ATTENTE, "Adhésions en attente",
            MemberStatus.ACTIF, "Membres actifs", MemberStatus.SUSPENDU, "Membres suspendus", MemberStatus.RADIE, "Membres radiés");
    private static final Map<LotStatus, String> LOT_LABELS = Map.of(LotStatus.EN_PREPARATION, "Lots en préparation",
            LotStatus.DISPONIBLE, "Lots disponibles", LotStatus.RESERVE, "Lots réservés (procédure en cours)",
            LotStatus.ATTRIBUE, "Lots attribués", LotStatus.INDISPONIBLE, "Lots indisponibles", LotStatus.LITIGIEUX, "Lots en litige");

    private final MemberRepository members;
    private final MemberObligationRepository obligations;
    private final PaymentTransactionRepository transactions;
    private final TransparencyService transparency;
    private final PropertyLotRepository lots;
    private final AllocationCampaignRepository campaigns;
    private final GeneralAssemblyRepository assemblies;
    private final NotificationRepository notifications;
    private final AppProperties props;
    private final Clock clock;

    public DashboardService(MemberRepository members, MemberObligationRepository obligations,
                            PaymentTransactionRepository transactions, TransparencyService transparency,
                            PropertyLotRepository lots, AllocationCampaignRepository campaigns,
                            GeneralAssemblyRepository assemblies, NotificationRepository notifications,
                            AppProperties props, Clock clock) {
        this.members = members;
        this.obligations = obligations;
        this.transactions = transactions;
        this.transparency = transparency;
        this.lots = lots;
        this.campaigns = campaigns;
        this.assemblies = assemblies;
        this.notifications = notifications;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard bureau(CurrentUser user) {
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        List<Section> sections = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        if (user.has(Permission.MEMBER_READ_ALL)) {
            List<Indicator> m = new ArrayList<>();
            for (MemberStatus s : MemberStatus.values()) {
                m.add(new Indicator("membres_" + s.name().toLowerCase(Locale.ROOT), MEMBER_LABELS.get(s),
                        members.countByStatus(s), "membres", "COMPTAGE", "Nombre de dossiers dont le statut actuel est " + s.name() + "."));
            }
            sections.add(new Section("membres", "Adhérents", m));
        }

        if (user.has(Permission.PAYMENT_READ_ALL) || user.has(Permission.ACCOUNTING_READ)) {
            BigDecimal expected = obligations.expectedBetween(monthStart, monthEnd);
            BigDecimal collected = obligations.paidOnDueBetween(monthStart, monthEnd);
            Integer rate = expected.signum() == 0 ? null
                    : collected.multiply(BigDecimal.valueOf(100)).divide(expected, 0, RoundingMode.HALF_UP).intValue();
            List<Object[]> overdue = obligations.overdueByMember(today);
            BigDecimal overdueTotal = overdue.stream().map(r -> (BigDecimal) r[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            Instant from = monthStart.atStartOfDay(ClockConfig.DAKAR).toInstant();
            Instant to = monthEnd.plusDays(1).atStartOfDay(ClockConfig.DAKAR).toInstant();
            sections.add(new Section("cotisations", "Cotisations du mois", List.of(
                    new Indicator("attendu_mois", "Cotisations attendues ce mois", expected, "XOF", "REEL",
                            "Somme des échéances de cotisation (hors annulées) dont la date d'échéance tombe dans le mois en cours."),
                    new Indicator("recouvre_mois", "Montant réglé sur ces échéances", collected, "XOF", "REEL",
                            "Part déjà réglée, par des paiements confirmés, des échéances du mois en cours."),
                    new Indicator("taux_recouvrement", "Taux de recouvrement du mois", rate, "%", "REEL",
                            "Montant réglé ÷ montant attendu pour les échéances du mois, arrondi à l'unité. Vide si rien n'est attendu."),
                    new Indicator("membres_en_retard", "Membres en retard", overdue.size(), "membres", "COMPTAGE",
                            "Membres ayant au moins une échéance échue (date d'échéance dépassée) non entièrement réglée."),
                    new Indicator("arrieres_total", "Arriérés totaux", overdueTotal, "XOF", "REEL",
                            "Somme des restes à payer des échéances échues, pénalités cristallisées comprises."),
                    new Indicator("paiements_reels_mois", "Paiements réels confirmés ce mois",
                            transactions.sumConfirmedBetween(PaymentTransaction.Status.CONFIRME, false, from, to), "XOF", "REEL",
                            "Paiements confirmés (webhook signé, vérification fournisseur ou double validation manuelle) ce mois-ci."),
                    new Indicator("paiements_simules_mois", "Paiements simulés ce mois",
                            transactions.sumConfirmedBetween(PaymentTransaction.Status.CONFIRME, true, from, to), "XOF", "SIMULATION",
                            "SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ. Jamais additionné aux montants réels."))));
        }

        if (user.has(Permission.ACCOUNTING_READ)) {
            TransparencySummary t = transparency.summary(today);
            sections.add(new Section("tresorerie", "Trésorerie et transparence", List.of(
                    new Indicator("tresorerie_comptable", "Trésorerie (solde comptable)", t.accountingTreasuryTotal(), "XOF", "REEL",
                            TransparencyService.DEFINITIONS.get("accountingTreasuryTotal")),
                    new Indicator("tresorerie_declaree", "Trésorerie (derniers soldes déclarés)", t.declaredTreasuryTotal(), "XOF", "DECLARE",
                            TransparencyService.DEFINITIONS.get("declaredTreasuryTotal")),
                    new Indicator("paiements_en_attente", "Demandes de paiement en attente", t.pendingPaymentsTotal(), "XOF", "EN_ATTENTE",
                            TransparencyService.DEFINITIONS.get("pendingPaymentsTotal")),
                    new Indicator("fonds_membres", "Fonds des membres détenus", t.memberFundsHeld(), "XOF", "REEL",
                            TransparencyService.DEFINITIONS.get("memberFundsHeld")),
                    new Indicator("depenses_engagees", "Dépenses engagées non payées", t.expensesCommittedTotal(), "XOF", "REEL",
                            TransparencyService.DEFINITIONS.get("expensesCommittedTotal")),
                    new Indicator("depenses_a_approuver", "Dépenses en attente d'approbation", t.expensesAwaitingApproval(), "dépenses", "COMPTAGE",
                            "Dépenses soumises qui n'ont pas encore reçu toutes les approbations requises."))));
            notices.add(t.escrowStatement());
            t.treasury().stream().filter(a -> "ECART".equals(a.status()))
                    .forEach(a -> notices.add("Écart de rapprochement sur le compte " + a.accountCode() + " (" + a.label() + ")."));
        }

        if (user.has(Permission.LOT_READ_ALL) || user.has(Permission.ALLOCATION_READ_ALL)) {
            List<Indicator> l = new ArrayList<>();
            Map<LotStatus, Long> counts = new EnumMap<>(LotStatus.class);
            for (Object[] row : lots.countByStatusGrouped()) {
                counts.put((LotStatus) row[0], (Long) row[1]);
            }
            for (LotStatus s : LotStatus.values()) {
                l.add(new Indicator("lots_" + s.name().toLowerCase(Locale.ROOT), LOT_LABELS.get(s),
                        counts.getOrDefault(s, 0L), "lots", "COMPTAGE", "Nombre de lots dont le statut actuel est " + s.name() + "."));
            }
            l.add(new Indicator("procedures_en_cours", "Procédures d'attribution en cours",
                    campaigns.countByStatusIn(EnumSet.complementOf(EnumSet.of(CampaignStatus.BROUILLON, CampaignStatus.ARCHIVEE,
                            CampaignStatus.ANNULEE))), "procédures", "COMPTAGE",
                    "Procédures publiées et ni archivées ni annulées."));
            sections.add(new Section("patrimoine", "Lots et attributions", l));
        }

        List<Indicator> gov = new ArrayList<>();
        assemblies.findFirstByStatusInAndScheduledAtAfterOrderByScheduledAtAsc(
                        EnumSet.of(GeneralAssembly.Status.PROGRAMMEE, GeneralAssembly.Status.CONVOQUEE), Instant.now(clock))
                .ifPresent(a -> gov.add(new Indicator("prochaine_ag", "Prochaine assemblée générale",
                        a.getTitle() + " — " + PdfSupport.formatDate(a.getScheduledAt()), null, "COMPTAGE",
                        "Prochaine assemblée programmée ou convoquée.")));
        if (user.has(Permission.NOTIFICATION_MANAGE)) {
            gov.add(new Indicator("notifications_echouees", "Notifications en échec", notifications.countByStatus(NotificationStatus.ECHOUE),
                    "notifications", "COMPTAGE", "Notifications dont toutes les tentatives d'envoi ont échoué."));
        }
        if (!gov.isEmpty()) {
            sections.add(new Section("gouvernance", "Gouvernance", gov));
        }
        if (props.payment().mockEnabled()) {
            notices.add("Mode simulation de paiement activé : SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ pour les paiements de test.");
        }
        return new Dashboard(primaryRole(user), today, Instant.now(clock), sections, notices);
    }

    private static String primaryRole(CurrentUser u) {
        for (String r : List.of("PRESIDENT", "TRESORIER", "SECRETAIRE", "AUDITEUR", "AGENT_DE_GESTION", "ADMINISTRATEUR_TECHNIQUE")) {
            if (u.roles().contains(r)) {
                return r;
            }
        }
        return "MEMBRE";
    }
}
