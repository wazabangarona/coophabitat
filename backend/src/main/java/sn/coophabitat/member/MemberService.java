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
import sn.coophabitat.contribution.PlanStatus;
import sn.coophabitat.member.MemberDtos.*;
import sn.coophabitat.security.AuthService;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.RoleCode;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
    private final Clock clock;

    public MemberService(MemberRepository members, MembershipStatusHistoryRepository history,
                         ContributionPlanRepository plans, UserAccountRepository users, AuthService auth,
                         ReferenceGenerator references, AuditService audit, Clock clock) {
        this.members = members;
        this.history = history;
        this.plans = plans;
        this.users = users;
        this.auth = auth;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Member> search(String q, MemberStatus status, MemberType type, Pageable pageable) {
        return members.search(q == null || q.isBlank() ? null : q.trim(), status, type, pageable);
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
            auth.invite(u);
            invited = true;
            audit.record(AuditCategory.SECURITE, "COMPTE_MEMBRE_CREE", "UserAccount", u.getId(),
                    Map.of("membre", m.getMemberNumber()));
        }
        return new CreatedMember(MemberResponse.of(m, invited), invited);
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
     * La radiation désactive le compte de connexion et révoque ses sessions.
     */
    @Transactional
    public Member changeStatus(Long id, StatusChangeRequest r, CurrentUser actor) {
        Member m = members.lockById(id).orElseThrow(() -> BusinessException.notFound("Membre"));
        MemberStatus from = m.getStatus();
        if (from == r.status()) {
            throw BusinessException.rule("STATUT_IDENTIQUE", "Le membre a déjà ce statut");
        }
        if (!from.allowedTargets().contains(r.status())) {
            throw BusinessException.rule("TRANSITION_INTERDITE", "Passage de " + from + " à " + r.status() + " non autorisé");
        }
        if (r.status() == MemberStatus.ACTIF && m.getContributionPlanId() == null) {
            throw BusinessException.rule("PLAN_REQUIS", "Affectez un plan de cotisation avant d'activer l'adhésion");
        }
        m.setStatus(r.status());
        recordHistory(id, from, r.status(), r.reason(), actor);
        audit.record(AuditCategory.MEMBRE, "STATUT_MEMBRE_MODIFIE", "Member", id,
                Map.of("de", from.name(), "vers", r.status().name(), "motif", r.reason()));
        if (r.status() == MemberStatus.RADIE) {
            users.findByMemberId(id).ifPresent(u -> {
                u.setEnabled(false);
                auth.revokeAllSessions(u.getId(), "RADIATION");
            });
        }
        return m;
    }

    @Transactional
    public Member assignPlan(Long id, Long planId) {
        Member m = get(id);
        ContributionPlan plan = requireActivePlan(planId);
        Long previous = m.getContributionPlanId();
        m.setContributionPlanId(plan.getId());
        audit.record(AuditCategory.FINANCE, "PLAN_AFFECTE", "Member", id,
                Map.of("ancien_plan", String.valueOf(previous), "nouveau_plan", plan.getCode()));
        return m;
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
