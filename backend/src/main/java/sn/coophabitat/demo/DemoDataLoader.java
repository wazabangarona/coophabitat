package sn.coophabitat.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.coophabitat.admin.CoopSettings;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.allocation.AllocationCampaign;
import sn.coophabitat.allocation.AllocationDtos.CampaignRequest;
import sn.coophabitat.allocation.AllocationMethod;
import sn.coophabitat.allocation.AllocationService;
import sn.coophabitat.assembly.AssemblyDtos.AgendaEntry;
import sn.coophabitat.assembly.AssemblyDtos.AssemblyRequest;
import sn.coophabitat.assembly.AssemblyService;
import sn.coophabitat.assembly.GeneralAssembly;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.contribution.*;
import sn.coophabitat.contribution.ContributionDtos.PlanRequest;
import sn.coophabitat.document.*;
import sn.coophabitat.finance.ExpenseCategory;
import sn.coophabitat.finance.ExpenseService;
import sn.coophabitat.finance.FinanceDtos.ExpenseRequest;
import sn.coophabitat.member.*;
import sn.coophabitat.member.MemberDtos.CreateMemberRequest;
import sn.coophabitat.payment.PaymentDtos.ManualPaymentRequest;
import sn.coophabitat.payment.PaymentIntent;
import sn.coophabitat.payment.PaymentService;
import sn.coophabitat.property.*;
import sn.coophabitat.property.PropertyDtos.LotRequest;
import sn.coophabitat.property.PropertyDtos.ProjectRequest;
import sn.coophabitat.reporting.PdfSupport;
import sn.coophabitat.security.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Jeu de données de démonstration, chargé uniquement si COOPHABITAT_DEMO_ENABLED=true, hors production,
 * avec un mot de passe fourni par COOPHABITAT_DEMO_PASSWORD (aucun mot de passe codé en dur).
 * Toutes les identités sont fictives et marquées comme telles ; les données passent par les services métier réels
 * (mêmes règles, mêmes écritures, même audit). Le chargement est idempotent.
 */
@Component
@Order(20)
@ConditionalOnProperty(prefix = "coophabitat.demo", name = "enabled", havingValue = "true")
public class DemoDataLoader implements ApplicationRunner {

    public static final String DEMO_DOMAIN = "demo.coophabitat.invalid";
    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final AppProperties props;
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final CoopSettingsRepository settings;
    private final MemberService members;
    private final MemberRepository memberRepository;
    private final PlanService plans;
    private final ObligationService obligations;
    private final MemberObligationRepository obligationRepository;
    private final PaymentService payments;
    private final PropertyService property;
    private final AllocationService allocations;
    private final AssemblyService assemblies;
    private final ExpenseService expenses;
    private final DocumentService documents;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DemoDataLoader(AppProperties props, UserAccountRepository users, PasswordEncoder encoder, AuthService auth,
                          CoopSettingsRepository settings, MemberService members, MemberRepository memberRepository,
                          PlanService plans, ObligationService obligations, MemberObligationRepository obligationRepository,
                          PaymentService payments, PropertyService property, AllocationService allocations,
                          AssemblyService assemblies, ExpenseService expenses, DocumentService documents,
                          JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.auth = auth;
        this.settings = settings;
        this.members = members;
        this.memberRepository = memberRepository;
        this.plans = plans;
        this.obligations = obligations;
        this.obligationRepository = obligationRepository;
        this.payments = payments;
        this.property = property;
        this.allocations = allocations;
        this.assemblies = assemblies;
        this.expenses = expenses;
        this.documents = documents;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    private record Person(String first, String last, MemberType type, int monthsAgo) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.isProduction()) {
            throw new IllegalStateException("Les données de démonstration sont interdites en production");
        }
        String password = props.demo().password();
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("COOPHABITAT_DEMO_PASSWORD doit contenir au moins 12 caractères");
        }
        if (users.findByEmailIgnoreCase("presidence@" + DEMO_DOMAIN).isPresent()) {
            log.info("Données de démonstration déjà présentes : aucun rechargement");
            return;
        }
        try {
            // Tout ou rien : un échec annule l'intégralité du jeu de démonstration.
            transactions.executeWithoutResult(status -> load(password));
        } finally {
            SecurityContextHolder.clearContext();
        }
        log.warn("Données de DÉMONSTRATION chargées (identités fictives, domaine {}).", DEMO_DOMAIN);
    }

    private void load(String password) {
        LocalDate today = LocalDate.now(clock);
        CoopSettings s = settings.current();
        s.setName("Coopérative d'habitat de démonstration");
        s.setLegalForm("Société coopérative d'habitat — DONNÉES FICTIVES DE DÉMONSTRATION");
        s.setCity("Ville fictive");
        s.setEmail("contact@" + DEMO_DOMAIN);
        s.setDemo(true);
        s.setUpdatedAt(Instant.now(clock));
        settings.save(s);

        CurrentUser president = staff("presidence", "Aminata Démo (présidente fictive)", "PRESIDENT", password);
        CurrentUser treasurer = staff("tresorerie", "Ousmane Exemple (trésorier fictif)", "TRESORIER", password);
        CurrentUser secretary = staff("secretariat", "Fatou Fictive (secrétaire fictive)", "SECRETAIRE", password);
        staff("audit", "Ibrahima Démo (auditeur fictif)", "AUDITEUR", password);
        staff("admin", "Administrateur technique (démo)", "ADMINISTRATEUR_TECHNIQUE", password);

        act(treasurer);
        ContributionPlan plan = plans.create(new PlanRequest("DEMO-MENSUEL", "Cotisation mensuelle (démonstration)",
                "Plan fictif utilisé pour la démonstration", BigDecimal.valueOf(25_000), Periodicity.MENSUELLE, 5,
                today.minusMonths(5).withDayOfMonth(1), null, 5, PenaltyMethod.FORFAIT, BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(5_000), RoundingMode.HALF_UP, "Délibération fictive DEMO-AG-01 (démonstration)"));
        plans.activate(plan.getId());
        jdbc.update("UPDATE contribution_plan SET is_demo = TRUE WHERE id = ?", plan.getId());

        act(secretary);
        List<Person> people = List.of(
                new Person("Mariama", "Démo", MemberType.ORDINAIRE, 30),
                new Person("Cheikh", "Exemple", MemberType.ORDINAIRE, 26),
                new Person("Aïssatou", "Fictive", MemberType.FONDATEUR, 20),
                new Person("Mamadou", "Démo", MemberType.ORDINAIRE, 18),
                new Person("Khady", "Exemple", MemberType.BENEFICIAIRE, 14),
                new Person("Babacar", "Fictif", MemberType.ORDINAIRE, 9),
                new Person("Ndeye", "Démo", MemberType.FONDATEUR, 7),
                new Person("Alioune", "Exemple", MemberType.ORDINAIRE, 4));
        List<Member> created = new ArrayList<>();
        int i = 1;
        for (Person p : people) {
            String email = "membre" + i + "@" + DEMO_DOMAIN;
            var res = members.create(new CreateMemberRequest(p.first(), p.last(), email, null,
                    "Adresse fictive n°" + i, "Ville fictive", today.minusMonths(p.monthsAgo()), p.type(), plan.getId(),
                    "Membre fictif de démonstration", false), secretary);
            if (i < people.size()) {
                members.changeStatus(res.member().id(), new MemberDtos.StatusChangeRequest(MemberStatus.ACTIF,
                        "Adhésion validée (démonstration)"), secretary);
            }
            Member m = memberRepository.findById(res.member().id()).orElseThrow();
            jdbc.update("UPDATE member SET is_demo = TRUE WHERE id = ?", m.getId());
            UserAccount u = new UserAccount();
            u.setEmail(email);
            u.setDisplayName(p.first() + " " + p.last() + " (membre fictif)");
            u.setMemberId(m.getId());
            u.setRoles(new HashSet<>(Set.of("MEMBRE")));
            u.setPasswordHash(encoder.encode(password));
            u.setPasswordChangedAt(Instant.now(clock));
            u.setEmailVerified(true);
            u.setDemo(true);
            users.save(u);
            created.add(m);
            i++;
        }

        act(treasurer);
        obligations.generateAll(today);
        obligations.assessPenalties(today);

        // Paiements : encaissements manuels saisis par le trésorier puis vérifiés par la présidente (double contrôle).
        int k = 0;
        for (Member m : created) {
            List<MemberObligation> open = obligationRepository.findByMemberIdOrderByDueDateAscIdAsc(m.getId()).stream()
                    .filter(o -> o.getDueDate().isBefore(today) && o.outstanding().signum() > 0).toList();
            int toPay = switch (k % 4) {
                case 0 -> open.size();
                case 1 -> Math.max(open.size() - 1, 0);
                case 2 -> Math.max(open.size() - 2, 0);
                default -> 0;
            };
            for (int j = 0; j < toPay; j++) {
                MemberObligation o = open.get(j);
                act(treasurer);
                PaymentIntent intent = payments.recordManual(new ManualPaymentRequest(m.getId(), o.outstanding(), o.getId(),
                        j % 2 == 0 ? "ESPECES" : "VIREMENT", "DEMO-" + m.getMemberNumber() + "-" + j,
                        o.getDueDate().plusDays(1).isAfter(today) ? today : o.getDueDate().plusDays(1),
                        "Encaissement fictif de démonstration"), UUID.randomUUID().toString(), treasurer);
                act(president);
                payments.verifyManual(intent.getId(), true, "Vérification de démonstration", president);
            }
            k++;
        }

        act(secretary);
        PropertyProject project = property.createProject(new ProjectRequest("DEMO-PRG-01", "Programme de démonstration",
                "Programme immobilier fictif : aucun terrain réel n'est associé à ces lots.", "Région fictive",
                "Département fictif", "Commune fictive", PropertyProject.Status.EN_COURS));
        jdbc.update("UPDATE property_project SET is_demo = TRUE WHERE id = ?", project.getId());
        List<Long> lotIds = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            PropertyLot lot = property.createLot(new LotRequest("DEMO-LOT-" + String.format("%02d", n), project.getId(),
                    n <= 4 ? LotType.TERRAIN : LotType.VILLA, "Région fictive", "Département fictif", "Commune fictive",
                    "Secteur fictif " + ((n + 1) / 2), BigDecimal.valueOf(n <= 4 ? 150 : 200), null, null, null, false,
                    "Lot fictif de démonstration"));
            property.changeStatus(lot.getId(), new PropertyDtos.LotStatusRequest(LotStatus.DISPONIBLE,
                    "Lot fictif ouvert à l'attribution (démonstration)"));
            jdbc.update("UPDATE property_lot SET is_demo = TRUE WHERE id = ?", lot.getId());
            if (n <= 3) {
                lotIds.add(lot.getId());
            }
        }

        AllocationCampaign campaign = allocations.create(new CampaignRequest("Attribution de démonstration — tranche 1",
                "Procédure fictive pour présenter le tirage au sort vérifiable.", AllocationMethod.TIRAGE_AU_SORT,
                "Membre actif depuis au moins 6 mois à la date de référence et à jour de ses cotisations.",
                "Tirage au sort public entre les candidats éligibles ; un lot par bénéficiaire ; liste d'attente pour les autres.",
                6, true, BigDecimal.ZERO, List.of("FONDATEUR", "ORDINAIRE", "BENEFICIAIRE"), today,
                Instant.now(clock).plus(14, ChronoUnit.DAYS), 2, Instant.now(clock).plus(21, ChronoUnit.DAYS),
                "Salle fictive"), secretary);
        allocations.setLots(campaign.getId(), lotIds);
        allocations.publishCriteria(campaign.getId());
        allocations.openApplications(campaign.getId());
        jdbc.update("UPDATE allocation_campaign SET is_demo = TRUE WHERE id = ?", campaign.getId());
        for (Member m : created.subList(0, 5)) {
            allocations.apply(campaign.getId(), m.getId());
        }

        GeneralAssembly ag = assemblies.create(new AssemblyRequest("Assemblée générale ordinaire (démonstration)",
                GeneralAssembly.Type.ORDINAIRE, Instant.now(clock).plus(30, ChronoUnit.DAYS), "Salle fictive",
                GeneralAssembly.Modality.PRESENTIEL, "Règle de quorum fictive : la moitié des membres actifs, présents ou représentés",
                "Assemblée fictive destinée à la démonstration de l'application."), secretary);
        assemblies.setAgenda(ag.getId(), List.of(
                new AgendaEntry("Approbation des comptes de l'exercice", "Présentation du rapport financier"),
                new AgendaEntry("Budget prévisionnel", null),
                new AgendaEntry("Point sur la procédure d'attribution", null)));
        assemblies.convene(ag.getId());
        jdbc.update("UPDATE general_assembly SET is_demo = TRUE WHERE id = ?", ag.getId());

        act(treasurer);
        byte[] pdf = PdfSupport.build(settings.current(), "Justificatif fictif de démonstration", "DEMO-JUSTIF-01",
                Instant.now(clock), doc -> PdfSupport.paragraph(doc,
                        "Document fictif généré pour la démonstration. Il ne correspond à aucune facture réelle."));
        StoredDocument justif = documents.store(new DocumentService.NewDocument("Justificatif fictif — fournitures",
                DocumentCategory.JUSTIFICATIF_DEPENSE, DocumentVisibility.BUREAU, null, null, null,
                "justificatif-demo.pdf", "application/pdf", pdf, true, false));
        var expense = expenses.create(new ExpenseRequest(ExpenseCategory.FOURNITURES, "Fournitures de bureau (démonstration)",
                "Fournisseur fictif", BigDecimal.valueOf(45_000), today.minusDays(2), justif.getId()), treasurer);
        expenses.submit(expense.getId(), treasurer);
        jdbc.update("UPDATE expense SET is_demo = TRUE WHERE id = ?", expense.getId());
        jdbc.update("UPDATE document SET is_demo = TRUE");
        jdbc.update("UPDATE user_account SET is_demo = TRUE WHERE lower(email) LIKE ?", "%@" + DEMO_DOMAIN);
    }

    private CurrentUser staff(String local, String name, String role, String password) {
        UserAccount u = new UserAccount();
        u.setEmail(local + "@" + DEMO_DOMAIN);
        u.setDisplayName(name);
        u.setRoles(new HashSet<>(Set.of(role)));
        u.setPasswordHash(encoder.encode(password));
        u.setPasswordChangedAt(Instant.now(clock));
        u.setEmailVerified(true);
        u.setDemo(true);
        return auth.toCurrentUser(users.save(u));
    }

    private static void act(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new CoopAuthentication(user, null));
    }
}
