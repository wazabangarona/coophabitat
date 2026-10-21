package sn.coophabitat.member;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.contribution.ContributionPlan;
import sn.coophabitat.contribution.ContributionPlanRepository;
import sn.coophabitat.contribution.ObligationService;
import sn.coophabitat.contribution.PlanStatus;
import sn.coophabitat.member.MemberDtos.*;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.security.AuthService;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.Permission;
import sn.coophabitat.security.PermissionResolver;
import sn.coophabitat.security.RoleCode;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MemberService {

    private final MemberRepository members;
    private final MembershipStatusHistoryRepository history;
    private final ContributionPlanRepository plans;
    private final UserAccountRepository users;
    private final AuthService auth;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final NotificationService notifications;
    private final PermissionResolver permissions;
    private final ObligationService obligations;
    private final Clock clock;

    public MemberService(MemberRepository members, MembershipStatusHistoryRepository history,
                         ContributionPlanRepository plans, UserAccountRepository users, AuthService auth,
                         ReferenceGenerator references, AuditService audit, NotificationService notifications,
                         PermissionResolver permissions, ObligationService obligations, Clock clock) {
        this.members = members;
        this.history = history;
        this.plans = plans;
        this.users = users;
        this.auth = auth;
        this.references = references;
        this.audit = audit;
        this.notifications = notifications;
        this.permissions = permissions;
        this.obligations = obligations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Member> search(String q, MemberStatus status, MemberType type, boolean withoutPlan, Pageable pageable) {
        return members.search(q == null || q.isBlank() ? null : q.trim(), status, type, withoutPlan, pageable);
    }

    @Transactional(readOnly = true)
    public Member get(Long id) {
        return members.findById(id).orElseThrow(() -> BusinessException.notFound("Membre"));
    }

    public boolean hasAccount(Long memberId) {
        return users.findByMemberId(memberId).isPresent();
    }

    @Transactional
    public CreatedMember create(CreateMemberRequest r, CurrentUser actor) {
        String email = normalizeEmail(r.email());
        String phone = normalizePhone(r.phone());
        checkUniqueness(email, phone, null);
        Member m = new Member();
        m.setMemberNumber(references.nextMemberNumber(r.membershipDate().getYear()));
        m.setFirstName(r.firstName().trim());
        m.setLastName(r.lastName().trim());
        m.setEmail(email);
        m.setPhone(phone);
        m.setAddress(r.address());
        m.setCity(r.city());
        m.setMembershipDate(r.membershipDate());
        m.setMemberType(r.memberType());
        m.setStatus(MemberStatus.EN_ATTENTE);
        m.setNotes(r.notes());
        if (r.contributionPlanId() != null) {
            m.setContributionPlanId(requireActivePlan(r.contributionPlanId()).getId());
        }
        m = members.saveAndFlush(m);
        recordHistory(m.getId(), null, MemberStatus.EN_ATTENTE, "Création du dossier d'adhésion", actor);
        audit.record(AuditCategory.MEMBRE, "MEMBRE_CREE", "Member", m.getId(),
                Map.of("numero", m.getMemberNumber(), "type", m.getMemberType().name()));
        boolean invited = false;
        String activationLink = null;
        if (r.createAccount()) {
            if (email == null) {
                throw BusinessException.badRequest("EMAIL_REQUIS", "Une adresse e-mail est nécessaire pour créer un compte");
            }
            if (users.findByEmailIgnoreCase(email).isPresent()) {
                throw BusinessException.conflict("COMPTE_EXISTANT", "Un compte utilise déjà cette adresse e-mail");
            }
            UserAccount u = new UserAccount();
            u.setEmail(email);
            u.setDisplayName(m.fullName());
            u.setMemberId(m.getId());
            u.setRoles(Set.of(RoleCode.MEMBRE.name()));
            u = users.save(u);
            var access = auth.inviteWithLink(u);
            invited = access.emailSent();
            activationLink = access.link();
            audit.record(AuditCategory.SECURITE, "COMPTE_MEMBRE_CREE", "UserAccount", u.getId(),
                    Map.of("membre", m.getMemberNumber()));
        }
        return new CreatedMember(MemberResponse.of(m, r.createAccount()), invited, activationLink);
    }

    @Transactional
    public Member update(Long id, UpdateMemberRequest r) {
        Member m = get(id);
        if (m.getVersion() != r.version()) {
            throw BusinessException.conflict("CONFLIT_CONCURRENT", "Le dossier a été modifié entre-temps. Rechargez-le.");
        }
        String email = normalizeEmail(r.email());
        String phone = normalizePhone(r.phone());
        checkUniqueness(email, phone, id);
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(m.getFirstName(), r.firstName())) changed.add("prénom");
        if (!Objects.equals(m.getLastName(), r.lastName())) changed.add("nom");
        if (!Objects.equals(m.getEmail(), email)) changed.add("e-mail");
        if (!Objects.equals(m.getPhone(), phone)) changed.add("téléphone");
        if (!Objects.equals(m.getAddress(), r.address())) changed.add("adresse");
        if (!Objects.equals(m.getCity(), r.city())) changed.add("ville");
        if (m.getMemberType() != r.memberType()) changed.add("type (" + m.getMemberType() + " → " + r.memberType() + ")");
        if (!Objects.equals(m.getNotes(), r.notes())) changed.add("notes");
        m.setFirstName(r.firstName().trim());
        m.setLastName(r.lastName().trim());
        m.setEmail(email);
        m.setPhone(phone);
        m.setAddress(r.address());
        m.setCity(r.city());
        m.setMemberType(r.memberType());
        m.setNotes(r.notes());
        if (!changed.isEmpty()) {
            // Seuls les noms des champs sont journalisés (minimisation des données personnelles dans l'audit).
            audit.record(AuditCategory.MEMBRE, "MEMBRE_MODIFIE", "Member", id, Map.of("champs", String.join(", ", changed)));
        }
        return m;
    }

    @Transactional
    public Member updateContact(Long id, ContactUpdateRequest r) {
        Member m = get(id);
        String email = normalizeEmail(r.email());
        String phone = normalizePhone(r.phone());
        checkUniqueness(email, phone, id);
        m.setEmail(email);
        m.setPhone(phone);
        m.setAddress(r.address());
        m.setCity(r.city());
        audit.record(AuditCategory.MEMBRE, "COORDONNEES_MODIFIEES", "Member", id, Map.of("par", "membre"));
        return m;
    }

    /**
     * Changement de statut. La suspension ou la radiation n'efface ni l'historique ni les obligations financières.
     * La radiation (comme le refus d'une demande) désactive le compte de connexion et révoque ses sessions.
     * L'approbation et le refus d'une demande en attente suivent les mêmes règles que {@link #approve} et {@link #reject}.
     */
    @Transactional
    public Member changeStatus(Long id, StatusChangeRequest r, CurrentUser actor) {
        Member m = members.lockById(id).orElseThrow(() -> BusinessException.notFound("Membre"));
        if (m.getStatus() == MemberStatus.EN_ATTENTE && r.status() == MemberStatus.ACTIF) {
            return approve(id, new ApprovalRequest(null, r.reason()), actor);
        }
        if (m.getStatus() == MemberStatus.EN_ATTENTE && r.status() == MemberStatus.REFUSE) {
            return reject(id, new RejectionRequest(r.reason()), actor);
        }
        if (actor != null && actor.isMember(id)) {
            throw BusinessException.forbidden("Vous ne pouvez pas changer le statut de votre propre adhésion");
        }
        transition(m, r.status(), r.reason(), actor, "STATUT_MEMBRE_MODIFIE", Map.of());
        return m;
    }

    /**
     * Approbation d'une demande d'adhésion, en une seule décision : affectation du plan de cotisation choisi
     * (ou conservation de celui déjà affecté par la trésorerie) puis activation. Une personne ne valide pas sa propre
     * adhésion, sauf à la mise en route (amorçage) : tant qu'aucun autre responsable habilité à valider n'a lui-même
     * une adhésion active, le premier bureau désigné par l'administrateur peut valider la sienne. Ce cas est
     * signalé dans le journal d'audit.
     */
    @Transactional
    public Member approve(Long id, ApprovalRequest r, CurrentUser actor) {
        Member m = members.lockById(id).orElseThrow(() -> BusinessException.notFound("Membre"));
        requirePending(m);
        boolean bootstrap = false;
        if (actor != null && actor.isMember(id)) {
            if (hasEstablishedApproverOtherThan(actor.userId())) {
                throw BusinessException.forbidden(
                        "Votre propre adhésion doit être validée par un autre membre du bureau (présidence ou secrétariat)");
            }
            bootstrap = true;
        }
        if (r.planId() != null && !r.planId().equals(m.getContributionPlanId())) {
            setPlan(m, requireActivePlan(r.planId()));
        } else if (m.getContributionPlanId() != null) {
            requireActivePlan(m.getContributionPlanId());
        }
        if (m.getContributionPlanId() == null) {
            throw BusinessException.rule("PLAN_REQUIS", "Choisissez le plan de cotisation du nouveau membre");
        }
        String reason = r.comment() == null || r.comment().isBlank() ? "Demande d'adhésion approuvée par le bureau" : r.comment().trim();
        Map<String, Object> details = new HashMap<>();
        details.put("plan", plans.findById(m.getContributionPlanId()).map(ContributionPlan::getCode).orElse("?"));
        if (bootstrap) {
            details.put("amorcage", "auto-validation du premier bureau");
        }
        transition(m, MemberStatus.ACTIF, reason, actor, "ADHESION_APPROUVEE", details);
        return m;
    }

    /** Refus motivé d'une demande d'adhésion : le compte de connexion éventuel est désactivé ; le refus peut être réexaminé. */
    @Transactional
    public Member reject(Long id, RejectionRequest r, CurrentUser actor) {
        Member m = members.lockById(id).orElseThrow(() -> BusinessException.notFound("Membre"));
        requirePending(m);
        if (actor != null && actor.isMember(id)) {
            throw BusinessException.forbidden("Vous ne pouvez pas statuer sur votre propre demande");
        }
        transition(m, MemberStatus.REFUSE, r.reason().trim(), actor, "ADHESION_REFUSEE", Map.of());
        return m;
    }

    /** Demandes d'adhésion à examiner (dossiers en attente), les plus anciennes d'abord. */
    @Transactional(readOnly = true)
    public ApplicationQueue applications(CurrentUser actor) {
        Map<Long, String> planNames = new HashMap<>();
        plans.findAll().forEach(p -> planNames.put(p.getId(), p.getName()));
        List<ApplicationItem> items = members.findByStatusOrderByCreatedAtAsc(MemberStatus.EN_ATTENTE).stream()
                .map(m -> {
                    List<MembershipStatusHistory> h = history.findByMemberIdOrderByChangedAtAscIdAsc(m.getId());
                    boolean online = !h.isEmpty() && h.getFirst().getChangedBy() == null;
                    return ApplicationItem.of(m, planNames.get(m.getContributionPlanId()), hasAccount(m.getId()), online,
                            actor != null && actor.isMember(m.getId()));
                }).toList();
        boolean canDecide = actor != null && actor.has(Permission.MEMBER_STATUS_CHANGE);
        boolean bootstrap = canDecide && !hasEstablishedApproverOtherThan(actor.userId());
        return new ApplicationQueue(items, canDecide, bootstrap);
    }

    /**
     * Un autre responsable habilité à valider les adhésions est-il déjà en place ? Compte actif portant un rôle doté de
     * MEMBER_STATUS_CHANGE et, s'il est rattaché à un dossier de membre, adhésion active.
     */
    public boolean hasEstablishedApproverOtherThan(Long userId) {
        List<String> roles = permissions.rolesWith(Permission.MEMBER_STATUS_CHANGE.name());
        if (roles.isEmpty()) {
            return false;
        }
        return users.findActiveByRoles(roles).stream()
                .filter(u -> !u.getId().equals(userId))
                .anyMatch(u -> u.getMemberId() == null
                        || members.findById(u.getMemberId()).map(x -> x.getStatus() == MemberStatus.ACTIF).orElse(false));
    }

    private static void requirePending(Member m) {
        if (m.getStatus() != MemberStatus.EN_ATTENTE) {
            throw BusinessException.rule("DEMANDE_DEJA_TRAITEE", "Cette demande n'est plus en attente (statut actuel : " + m.getStatus() + ")");
        }
    }

    private void transition(Member m, MemberStatus to, String reason, CurrentUser actor, String event,
                            Map<String, Object> extra) {
        MemberStatus from = m.getStatus();
        if (from == to) {
            throw BusinessException.rule("STATUT_IDENTIQUE", "Le membre a déjà ce statut");
        }
        if (!from.allowedTargets().contains(to)) {
            throw BusinessException.rule("TRANSITION_INTERDITE", "Passage de " + from + " à " + to + " non autorisé");
        }
        if (to == MemberStatus.ACTIF && m.getContributionPlanId() == null) {
            throw BusinessException.rule("PLAN_REQUIS", "Affectez un plan de cotisation avant d'activer l'adhésion");
        }
        Long id = m.getId();
        m.setStatus(to);
        recordHistory(id, from, to, reason, actor);
        Map<String, Object> details = new HashMap<>(extra);
        details.put("de", from.name());
        details.put("vers", to.name());
        details.put("motif", reason);
        audit.record(AuditCategory.MEMBRE, event, "Member", id, details);
        if (to == MemberStatus.ACTIF) {
            generateObligations(m);
        }
        if (to == MemberStatus.RADIE || to == MemberStatus.REFUSE) {
            users.findByMemberId(id).ifPresent(u -> {
                u.setEnabled(false);
                auth.revokeAllSessions(u.getId(), to == MemberStatus.RADIE ? "RADIATION" : "ADHESION_REFUSEE");
            });
        } else if (from == MemberStatus.REFUSE) {
            users.findByMemberId(id).ifPresent(u -> u.setEnabled(true));
        }
        notifyStatus(m, from, to, reason);
    }

    private void notifyStatus(Member m, MemberStatus from, MemberStatus to, String reason) {
        String subject;
        String body;
        switch (to) {
            case ACTIF -> {
                subject = from == MemberStatus.EN_ATTENTE ? "Votre adhésion est validée" : "Votre adhésion est de nouveau active";
                body = "Bonjour " + m.getFirstName() + ",\n\nLe bureau de la coopérative a "
                        + (from == MemberStatus.EN_ATTENTE ? "validé" : "réactivé") + " votre adhésion (n° " + m.getMemberNumber()
                        + "). Vous pouvez désormais consulter vos échéances, payer vos cotisations et candidater aux attributions"
                        + " depuis votre espace membre.";
            }
            case REFUSE -> {
                subject = "Votre demande d'adhésion n'a pas été retenue";
                body = "Bonjour " + m.getFirstName() + ",\n\nLe bureau de la coopérative n'a pas retenu votre demande d'adhésion."
                        + "\nMotif : " + reason + "\n\nPour toute question, adressez-vous au bureau.";
            }
            case SUSPENDU -> {
                subject = "Votre adhésion est suspendue";
                body = "Bonjour " + m.getFirstName() + ",\n\nVotre adhésion est suspendue.\nMotif : " + reason;
            }
            case RADIE -> {
                subject = "Votre adhésion a pris fin";
                body = "Bonjour " + m.getFirstName() + ",\n\nVotre adhésion a pris fin (radiation).\nMotif : " + reason;
            }
            case EN_ATTENTE -> {
                subject = "Votre demande d'adhésion est réexaminée";
                body = "Bonjour " + m.getFirstName() + ",\n\nVotre demande d'adhésion est de nouveau à l'examen du bureau.";
            }
            default -> {
                return;
            }
        }
        notifications.notifyMember(m.getId(), "STATUT_ADHESION_" + to.name(), subject, body,
                "STATUT_ADHESION:" + m.getId() + ":" + to.name() + ":" + Instant.now(clock).toEpochMilli(), "Member", m.getId());
    }

    @Transactional
    public Member assignPlan(Long id, Long planId, CurrentUser actor) {
        if (actor != null && actor.isMember(id)) {
            throw BusinessException.forbidden("Votre propre plan de cotisation est affecté par un autre membre du bureau");
        }
        Member m = get(id);
        ContributionPlan plan = requireActivePlan(planId);
        if (m.getStatus() == MemberStatus.RADIE || m.getStatus() == MemberStatus.REFUSE) {
            throw BusinessException.rule("PLAN_IMPOSSIBLE", "Aucun plan ne s'affecte à un membre radié ou à une demande refusée");
        }
        setPlan(m, plan);
        return m;
    }

    /**
     * Affectation groupée d'un plan (par exemple tous les membres encore sans plan). Les membres radiés, les demandes
     * refusées, le dossier de l'utilisateur lui-même et ceux qui ont déjà ce plan sont laissés tels quels ; chaque
     * affectation est journalisée séparément.
     */
    @Transactional
    public BulkPlanResult assignPlan(List<Long> ids, Long planId, CurrentUser actor) {
        ContributionPlan plan = requireActivePlan(planId);
        int updated = 0;
        int unchanged = 0;
        int excluded = 0;
        for (Long id : new java.util.LinkedHashSet<>(ids)) {
            Member m = get(id);
            if (m.getStatus() == MemberStatus.RADIE || m.getStatus() == MemberStatus.REFUSE
                    || (actor != null && actor.isMember(id))) {
                excluded++;
            } else if (plan.getId().equals(m.getContributionPlanId())) {
                unchanged++;
            } else {
                setPlan(m, plan);
                updated++;
            }
        }
        return new BulkPlanResult(updated, unchanged, excluded);
    }

    private void setPlan(Member m, ContributionPlan plan) {
        Long previous = m.getContributionPlanId();
        if (plan.getId().equals(previous)) {
            return;
        }
        m.setContributionPlanId(plan.getId());
        audit.record(AuditCategory.FINANCE, "PLAN_AFFECTE", "Member", m.getId(),
                Map.of("ancien_plan", String.valueOf(previous), "nouveau_plan", plan.getCode()));
        generateObligations(m);
    }

    /**
     * Les échéances d'un membre qui devient actif, ou qui change de plan, apparaissent tout de suite dans son espace,
     * sans attendre la génération nocturne (sans effet pour un membre en attente ou radié).
     */
    private void generateObligations(Member m) {
        members.flush();
        obligations.generateForMember(m.getId(), obligations.today());
    }

    @Transactional(readOnly = true)
    public List<MembershipStatusHistory> history(Long id) {
        get(id);
        return history.findByMemberIdOrderByChangedAtAscIdAsc(id);
    }

    private ContributionPlan requireActivePlan(Long planId) {
        ContributionPlan plan = plans.findById(planId).orElseThrow(() -> BusinessException.notFound("Plan de cotisation"));
        if (plan.getStatus() != PlanStatus.ACTIF) {
            throw BusinessException.rule("PLAN_INACTIF", "Seul un plan actif peut être affecté");
        }
        return plan;
    }

    private void recordHistory(Long memberId, MemberStatus from, MemberStatus to, String reason, CurrentUser actor) {
        MembershipStatusHistory h = new MembershipStatusHistory();
        h.setMemberId(memberId);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setReason(reason);
        h.setChangedBy(actor == null ? null : actor.userId());
        h.setChangedAt(Instant.now(clock));
        history.save(h);
    }

    private void checkUniqueness(String email, String phone, Long selfId) {
        if (email != null) {
            members.findByEmailIgnoreCase(email).filter(x -> !x.getId().equals(selfId)).ifPresent(x -> {
                throw BusinessException.conflict("EMAIL_EXISTANT", "Un autre membre utilise déjà cette adresse e-mail");
            });
        }
        if (phone != null) {
            members.findByPhone(phone).filter(x -> !x.getId().equals(selfId)).ifPresent(x -> {
                throw BusinessException.conflict("TELEPHONE_EXISTANT", "Un autre membre utilise déjà ce numéro");
            });
        }
    }

    static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    /** Normalise vers le format E.164 ; les numéros sénégalais à 9 chiffres reçoivent l'indicatif +221. */
    static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (!digits.startsWith("+") && digits.length() == 9) {
            digits = "+221" + digits;
        } else if (!digits.startsWith("+")) {
            digits = "+" + digits;
        }
        return digits;
    }
}
