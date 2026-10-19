package sn.coophabitat.allocation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.allocation.AllocationDtos.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Json;
import sn.coophabitat.common.Money;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.document.DocumentCategory;
import sn.coophabitat.document.DocumentService;
import sn.coophabitat.document.DocumentVisibility;
import sn.coophabitat.document.StoredDocument;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.member.MemberType;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.property.LotStatus;
import sn.coophabitat.property.PropertyLot;
import sn.coophabitat.property.PropertyLotRepository;
import sn.coophabitat.property.PropertyService;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.Permission;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Procédure d'attribution des lots : un processus explicite en étapes, et non un simple bouton « Attribuer ».
 * <ul>
 *   <li>Chaque étape vérifie l'état courant sous verrou (SELECT … FOR UPDATE) : aucune étape ne peut être
 *       rejouée silencieusement (notamment l'exécution du tirage).</li>
 *   <li>Les validations exigent plusieurs responsables distincts ; un responsable candidat ne peut pas valider.</li>
 *   <li>Un lot ne peut avoir qu'un seul résultat actif (index unique partiel en base).</li>
 *   <li>Toute correction passe par une annulation formelle, motivée et validée.</li>
 * </ul>
 */
@Service
public class AllocationService {

    private static final Set<CampaignStatus> EDITABLE = EnumSet.of(CampaignStatus.BROUILLON);
    private static final Set<CampaignStatus> CANCELLABLE = EnumSet.of(CampaignStatus.BROUILLON,
            CampaignStatus.CRITERES_PUBLIES, CampaignStatus.CANDIDATURES_OUVERTES, CampaignStatus.CANDIDATURES_CLOSES,
            CampaignStatus.LISTE_PUBLIEE, CampaignStatus.VALIDEE);
    private static final Set<AllocationResult.Status> ACTIVE = EnumSet.of(AllocationResult.Status.PROVISOIRE,
            AllocationResult.Status.DEFINITIF);

    private final AllocationCampaignRepository campaigns;
    private final AllocationCandidateRepository candidates;
    private final AllocationResultRepository results;
    private final AllocationApprovalRepository approvals;
    private final AllocationMinutesRepository minutes;
    private final AllocationContestRepository contests;
    private final CommissionMemberRepository commission;
    private final PropertyLotRepository lots;
    private final PropertyService property;
    private final MemberRepository members;
    private final UserAccountRepository users;
    private final EligibilityEvaluator eligibility;
    private final MinutesPdfGenerator minutesPdf;
    private final DocumentService documents;
    private final NotificationService notifications;
    private final CoopSettingsRepository settings;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final Clock clock;

    public AllocationService(AllocationCampaignRepository campaigns, AllocationCandidateRepository candidates,
                             AllocationResultRepository results, AllocationApprovalRepository approvals,
                             AllocationMinutesRepository minutes, AllocationContestRepository contests,
                             CommissionMemberRepository commission, PropertyLotRepository lots,
                             PropertyService property, MemberRepository members, UserAccountRepository users,
                             EligibilityEvaluator eligibility, MinutesPdfGenerator minutesPdf,
                             DocumentService documents, NotificationService notifications,
                             CoopSettingsRepository settings, ReferenceGenerator references, AuditService audit,
                             Clock clock) {
        this.campaigns = campaigns;
        this.candidates = candidates;
        this.results = results;
        this.approvals = approvals;
        this.minutes = minutes;
        this.contests = contests;
        this.commission = commission;
        this.lots = lots;
        this.property = property;
        this.members = members;
        this.users = users;
        this.eligibility = eligibility;
        this.minutesPdf = minutesPdf;
        this.documents = documents;
        this.notifications = notifications;
        this.settings = settings;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================== préparation

    @Transactional
    public AllocationCampaign create(CampaignRequest r, CurrentUser user) {
        AllocationCampaign c = new AllocationCampaign();
        c.setReference(references.next("ATT"));
        apply(c, r);
        c.setCreatedBy(user.userId());
        c = campaigns.save(c);
        audit.record(AuditCategory.ATTRIBUTION, "PROCEDURE_CREEE", "AllocationCampaign", c.getId(),
                Map.of("reference", c.getReference(), "methode", c.getMethod().name()));
        return c;
    }

    @Transactional
    public AllocationCampaign update(Long id, CampaignRequest r) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EDITABLE, "Les critères ne sont modifiables qu'en brouillon (ils sont figés une fois publiés)");
        apply(c, r);
        audit.record(AuditCategory.ATTRIBUTION, "PROCEDURE_MODIFIEE", "AllocationCampaign", id, Map.of());
        return c;
    }

    @Transactional
    public AllocationCampaign setLots(Long id, List<Long> lotIds) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EDITABLE, "Les lots ne sont modifiables qu'en brouillon");
        for (Long lotId : lotIds) {
            PropertyLot l = lots.findById(lotId).orElseThrow(() -> BusinessException.notFound("Lot " + lotId));
            if (l.getStatus() != LotStatus.DISPONIBLE) {
                throw BusinessException.rule("LOT_INDISPONIBLE", "Le lot " + l.getReference() + " n'est pas disponible");
            }
        }
        c.getLotIds().clear();
        c.getLotIds().addAll(new LinkedHashSet<>(lotIds));
        audit.record(AuditCategory.ATTRIBUTION, "LOTS_DEFINIS", "AllocationCampaign", id, Map.of("lots", lotIds.toString()));
        return c;
    }

    @Transactional
    public List<CommissionMember> setCommission(Long id, List<CommissionEntry> entries) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EDITABLE, "La commission ne se compose qu'en brouillon");
        if (c.getMethod() != AllocationMethod.COMMISSION) {
            throw BusinessException.rule("METHODE", "Cette procédure n'utilise pas de commission");
        }
        commission.deleteAll(commission.findByCampaignId(id));
        commission.flush();
        List<CommissionMember> saved = new ArrayList<>();
        for (CommissionEntry e : entries) {
            users.findById(e.userId()).orElseThrow(() -> BusinessException.notFound("Utilisateur"));
            CommissionMember m = new CommissionMember();
            m.setCampaignId(id);
            m.setUserId(e.userId());
            m.setRoleInCommission(e.role());
            m.setConflictDeclared(e.conflictDeclared());
            m.setConflictDetails(e.conflictDetails());
            saved.add(commission.save(m));
        }
        audit.record(AuditCategory.ATTRIBUTION, "COMMISSION_COMPOSEE", "AllocationCampaign", id,
                Map.of("membres", entries.stream().map(CommissionEntry::userId).toList().toString(),
                        "conflits_declares", entries.stream().filter(CommissionEntry::conflictDeclared).count()));
        return saved;
    }

    /** Publication des critères et des règles : ils deviennent immuables. */
    @Transactional
    public AllocationCampaign publishCriteria(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EDITABLE, "Critères déjà publiés");
        if (c.getLotIds().isEmpty()) {
            throw BusinessException.rule("LOTS_REQUIS", "Définissez au moins un lot");
        }
        if (blank(c.getEligibilityText()) || blank(c.getRulesText()) || c.getReferenceDate() == null) {
            throw BusinessException.rule("CRITERES_INCOMPLETS",
                    "Les critères d'éligibilité, les règles de sélection et la date de référence sont obligatoires");
        }
        if (c.getMethod() == AllocationMethod.COMMISSION
                && commission.findByCampaignId(id).stream().filter(m -> !m.isConflictDeclared()).count() < c.getRequiredApprovals()) {
            throw BusinessException.rule("COMMISSION_INCOMPLETE",
                    "La commission doit compter au moins " + c.getRequiredApprovals() + " membres sans conflit d'intérêts");
        }
        c.setStatus(CampaignStatus.CRITERES_PUBLIES);
        c.setCriteriaPublishedAt(Instant.now(clock));
        audit.record(AuditCategory.ATTRIBUTION, "CRITERES_PUBLIES", "AllocationCampaign", id,
                Map.of("criteres", c.getEligibilityText(), "regles", c.getRulesText(),
                        "date_reference", c.getReferenceDate().toString()));
        for (Member m : members.findByStatusIn(List.of(MemberStatus.ACTIF))) {
            notifications.notifyMember(m.getId(), "PROCEDURE_ATTRIBUTION", "Nouvelle procédure d'attribution : " + c.getTitle(),
                    "Une procédure d'attribution a été publiée (" + c.getReference() + "). Les critères et les règles "
                            + "sont consultables dans votre espace membre.", "ATT_CRITERES:" + id + ":" + m.getId(),
                    "AllocationCampaign", id);
        }
        return c;
    }

    @Transactional
    public AllocationCampaign openApplications(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.CRITERES_PUBLIES), "Publiez d'abord les critères");
        c.setStatus(CampaignStatus.CANDIDATURES_OUVERTES);
        c.setApplicationOpensAt(Instant.now(clock));
        audit.record(AuditCategory.ATTRIBUTION, "CANDIDATURES_OUVERTES", "AllocationCampaign", id, Map.of());
        return c;
    }

    // ================================================================== candidatures

    @Transactional
    public AllocationCandidate apply(Long id, Long memberId) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.CANDIDATURES_OUVERTES), "Les candidatures ne sont pas ouvertes");
        if (c.getApplicationClosesAt() != null && Instant.now(clock).isAfter(c.getApplicationClosesAt())) {
            throw BusinessException.rule("CANDIDATURES_CLOSES", "La période de candidature est terminée");
        }
        Member m = members.findById(memberId).orElseThrow(() -> BusinessException.notFound("Membre"));
        if (m.getStatus() != MemberStatus.ACTIF) {
            throw BusinessException.rule("ADHESION_INACTIVE", "Seuls les membres actifs peuvent candidater");
        }
        var existing = candidates.findByCampaignIdAndMemberId(id, memberId);
        if (existing.isPresent()) {
            AllocationCandidate cand = existing.get();
            if (cand.getWithdrawnAt() == null) {
                throw BusinessException.conflict("DEJA_CANDIDAT", "Vous êtes déjà candidat à cette procédure");
            }
            cand.setWithdrawnAt(null);
            cand.setAppliedAt(Instant.now(clock));
            audit.record(AuditCategory.ATTRIBUTION, "CANDIDATURE_RENOUVELEE", "AllocationCandidate", cand.getId(), Map.of());
            return cand;
        }
        AllocationCandidate cand = new AllocationCandidate();
        cand.setCampaignId(id);
        cand.setMemberId(memberId);
        cand.setAppliedAt(Instant.now(clock));
        cand = candidates.save(cand);
        audit.record(AuditCategory.ATTRIBUTION, "CANDIDATURE_DEPOSEE", "AllocationCandidate", cand.getId(),
                Map.of("procedure", c.getReference(), "membre", m.getMemberNumber()));
        return cand;
    }

    @Transactional
    public AllocationCandidate withdraw(Long id, Long memberId) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.CANDIDATURES_OUVERTES), "Le retrait n'est possible que pendant la période de candidature");
        AllocationCandidate cand = candidates.findByCampaignIdAndMemberId(id, memberId)
                .filter(x -> x.getWithdrawnAt() == null)
                .orElseThrow(() -> BusinessException.notFound("Candidature"));
        cand.setWithdrawnAt(Instant.now(clock));
        audit.record(AuditCategory.ATTRIBUTION, "CANDIDATURE_RETIREE", "AllocationCandidate", cand.getId(), Map.of());
        return cand;
    }

    @Transactional
    public AllocationCampaign closeApplications(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.CANDIDATURES_OUVERTES), "Les candidatures ne sont pas ouvertes");
        c.setStatus(CampaignStatus.CANDIDATURES_CLOSES);
        if (c.getApplicationClosesAt() == null || c.getApplicationClosesAt().isAfter(Instant.now(clock))) {
            c.setApplicationClosesAt(Instant.now(clock));
        }
        audit.record(AuditCategory.ATTRIBUTION, "CANDIDATURES_CLOSES", "AllocationCampaign", id, Map.of());
        evaluateAll(c);
        return c;
    }

    /** Vérification (ou nouvelle vérification) de l'éligibilité à la date de référence. */
    @Transactional
    public AllocationCampaign checkEligibility(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.CANDIDATURES_CLOSES), "L'éligibilité se vérifie après la clôture des candidatures");
        evaluateAll(c);
        return c;
    }

    private void evaluateAll(AllocationCampaign c) {
        int eligible = 0;
        int total = 0;
        for (AllocationCandidate cand : candidates.findByCampaignIdOrderByIdAsc(c.getId())) {
            if (cand.getWithdrawnAt() != null) {
                continue;
            }
            Member m = members.findById(cand.getMemberId()).orElseThrow();
            EligibilityEvaluator.Evaluation e = eligibility.evaluate(c, m);
            cand.setEligibilityStatus(e.eligible() ? AllocationCandidate.Eligibility.ELIGIBLE : AllocationCandidate.Eligibility.NON_ELIGIBLE);
            cand.setEligibilityReason(e.reason());
            cand.setEligibilitySnapshot(Json.write(e.snapshot()));
            cand.setCheckedAt(Instant.now(clock));
            total++;
            if (e.eligible()) {
                eligible++;
            }
        }
        audit.record(AuditCategory.ATTRIBUTION, "ELIGIBILITE_VERIFIEE", "AllocationCampaign", c.getId(),
                Map.of("candidats", total, "eligibles", eligible, "date_reference", c.getReferenceDate().toString()));
    }

    /**
     * Publication de la liste des candidatures retenues : la liste est figée, les empreintes calculées,
     * l'engagement cryptographique sur la graine est enregistré et les lots passent à RESERVE.
     */
    @Transactional
    public AllocationCampaign publishList(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.CANDIDATURES_CLOSES), "Clôturez d'abord les candidatures");
        List<AllocationCandidate> eligible = eligibleCandidates(id);
        if (eligible.isEmpty()) {
            throw BusinessException.rule("AUCUN_ELIGIBLE", "Aucun candidat éligible : la procédure ne peut pas se poursuivre");
        }
        if (eligible.stream().anyMatch(x -> x.getCheckedAt() == null)) {
            throw BusinessException.rule("ELIGIBILITE_NON_VERIFIEE", "Vérifiez l'éligibilité de tous les candidats");
        }
        List<PropertyLot> locked = lots.lockByIds(c.getLotIds());
        for (PropertyLot l : locked) {
            if (l.getStatus() != LotStatus.DISPONIBLE) {
                throw BusinessException.rule("LOT_INDISPONIBLE", "Le lot " + l.getReference() + " n'est plus disponible");
            }
        }
        c.setParticipantsDigest(DrawEngine.participantsDigest(c.getReference(), memberNumbers(eligible)));
        c.setLotsDigest(DrawEngine.lotsDigest(c.getReference(), lotReferences(locked)));
        if (c.getMethod() == AllocationMethod.TIRAGE_AU_SORT) {
            String seed = DrawEngine.newSeed();
            c.setSeedSecret(seed);
            c.setSeedCommitment(DrawEngine.commitment(seed));
            c.setAlgorithmVersion(DrawEngine.VERSION);
        }
        c.setFrozenAt(Instant.now(clock));
        c.setListPublishedAt(Instant.now(clock));
        c.setStatus(CampaignStatus.LISTE_PUBLIEE);
        for (PropertyLot l : locked) {
            property.transitionFromProcedure(l, LotStatus.RESERVE, "Réservé pour la procédure " + c.getReference(), id);
        }
        audit.record(AuditCategory.ATTRIBUTION, "LISTE_PUBLIEE", "AllocationCampaign", id, Map.of(
                "eligibles", eligible.size(), "empreinte_participants", c.getParticipantsDigest(),
                "empreinte_lots", c.getLotsDigest(), "engagement_graine", String.valueOf(c.getSeedCommitment())));
        for (AllocationCandidate cand : candidates.findByCampaignIdOrderByIdAsc(id)) {
            if (cand.getWithdrawnAt() == null) {
                boolean ok = cand.getEligibilityStatus() == AllocationCandidate.Eligibility.ELIGIBLE;
                notifications.notifyMember(cand.getMemberId(), "ELIGIBILITE_ATTRIBUTION",
                        "Procédure " + c.getReference() + " : " + (ok ? "candidature retenue" : "candidature non retenue"),
                        ok ? "Votre candidature est retenue pour la procédure « " + c.getTitle() + " »."
                                : "Votre candidature n'est pas retenue : " + cand.getEligibilityReason()
                                + ". Vous pouvez formuler une réclamation auprès du bureau.",
                        "ATT_LISTE:" + id + ":" + cand.getId(), "AllocationCampaign", id);
            }
        }
        return c;
    }

    // ================================================================== validations

    @Transactional
    public AllocationCampaign approve(Long id, ApprovalRequest r, CurrentUser user) {
        AllocationCampaign c = lock(id);
        switch (r.step()) {
            case VALIDATION_LISTE -> requireStatus(c, EnumSet.of(CampaignStatus.LISTE_PUBLIEE), "La liste n'est pas en attente de validation");
            case VALIDATION_PV -> {
                requireStatus(c, EnumSet.of(CampaignStatus.EXECUTEE), "Aucun procès-verbal en attente de validation");
                AllocationMinutes m = minutes.findTopByCampaignIdOrderByVersionNoDesc(id)
                        .filter(x -> x.getStatus() == AllocationMinutes.Status.BROUILLON)
                        .orElseThrow(() -> BusinessException.rule("PV_ABSENT", "Générez le procès-verbal avant de le valider"));
                if (r.targetId() != null && !r.targetId().equals(m.getId())) {
                    throw BusinessException.rule("PV_PERIME", "Une version plus récente du procès-verbal existe");
                }
            }
            case DECISION_COMMISSION -> {
                requireStatus(c, EnumSet.of(CampaignStatus.VALIDEE), "Les décisions de la commission ne sont pas en cours");
                requireCommissionMember(c, user);
                if (results.findByCampaignIdAndStatusIn(id, EnumSet.of(AllocationResult.Status.PROVISOIRE)).isEmpty()) {
                    throw BusinessException.rule("AUCUNE_DECISION", "Aucune décision à valider");
                }
            }
            case ANNULATION_RESULTAT -> throw BusinessException.badRequest("ETAPE", "Utilisez la demande d'annulation de résultat");
        }
        if (!user.has(Permission.ALLOCATION_APPROVE) && r.step() != AllocationApproval.Step.DECISION_COMMISSION) {
            throw BusinessException.forbidden("Droit de validation requis");
        }
        requireNoConflict(c, user);
        Long target = r.step() == AllocationApproval.Step.VALIDATION_PV
                ? minutes.findTopByCampaignIdOrderByVersionNoDesc(id).map(AllocationMinutes::getId).orElse(null) : null;
        recordApproval(c, r.step(), user, r.decision(), r.comment(), target);
        if (r.decision() == AllocationApproval.Decision.REFUSE) {
            return c;
        }
        long count = approvedCount(id, r.step(), target);
        if (count < c.getRequiredApprovals()) {
            return c;
        }
        switch (r.step()) {
            case VALIDATION_LISTE -> {
                c.setStatus(CampaignStatus.VALIDEE);
                audit.record(AuditCategory.ATTRIBUTION, "LISTE_VALIDEE", "AllocationCampaign", id, Map.of("validations", count));
            }
            case DECISION_COMMISSION -> {
                c.setStatus(CampaignStatus.EXECUTEE);
                c.setExecutedAt(Instant.now(clock));
                c.setExecutedBy(user.userId());
                audit.record(AuditCategory.ATTRIBUTION, "DECISIONS_COMMISSION_VALIDEES", "AllocationCampaign", id, Map.of("validations", count));
                generateMinutesInternal(c, user);
            }
            case VALIDATION_PV -> finalizeMinutes(c);
            default -> {
            }
        }
        return c;
    }

    // ================================================================== exécution

    /** Exécution unique du tirage au sort validé. Une seconde exécution est impossible (état + verrou). */
    @Transactional
    public AllocationCampaign executeDraw(Long id, String publicEntropy, CurrentUser user) {
        AllocationCampaign c = lock(id);
        if (c.getMethod() != AllocationMethod.TIRAGE_AU_SORT) {
            throw BusinessException.rule("METHODE", "Cette procédure relève d'une commission");
        }
        if (c.getExecutedAt() != null || c.getStatus() == CampaignStatus.EXECUTEE) {
            throw BusinessException.conflict("DEJA_EXECUTE", "Le tirage a déjà été exécuté ; une correction exige une annulation formelle");
        }
        requireStatus(c, EnumSet.of(CampaignStatus.VALIDEE), "Le tirage n'est possible qu'après validation de la liste");
        requireNoConflict(c, user);
        List<AllocationCandidate> eligible = eligibleCandidates(id);
        List<String> numbers = memberNumbers(eligible);
        List<PropertyLot> campaignLots = lots.lockByIds(c.getLotIds()).stream()
                .sorted(Comparator.comparing(PropertyLot::getReference)).toList();
        // Contrôle d'intégrité : la liste et les lots n'ont pas changé depuis leur publication.
        if (!DrawEngine.participantsDigest(c.getReference(), numbers).equals(c.getParticipantsDigest())
                || !DrawEngine.lotsDigest(c.getReference(), lotReferences(campaignLots)).equals(c.getLotsDigest())) {
            throw BusinessException.conflict("LISTE_ALTEREE", "La liste figée ne correspond plus aux empreintes publiées");
        }
        for (PropertyLot l : campaignLots) {
            if (l.getStatus() != LotStatus.RESERVE || results.existsByLotIdAndStatusIn(l.getId(), ACTIVE)) {
                throw BusinessException.conflict("DOUBLE_ATTRIBUTION", "Le lot " + l.getReference() + " est déjà engagé");
            }
        }
        String entropy = publicEntropy == null ? "" : publicEntropy.trim();
        List<String> order = DrawEngine.shuffle(numbers, DrawEngine.finalSeed(c.getSeedSecret(), c.getParticipantsDigest(),
                c.getLotsDigest(), entropy));
        Map<String, AllocationCandidate> byNumber = new HashMap<>();
        for (AllocationCandidate cand : eligible) {
            byNumber.put(members.findById(cand.getMemberId()).orElseThrow().getMemberNumber(), cand);
        }
        for (int i = 0; i < order.size(); i++) {
            AllocationResult res = new AllocationResult();
            res.setCampaignId(id);
            res.setCandidateId(byNumber.get(order.get(i)).getId());
            res.setDrawRank(i + 1);
            if (i < campaignLots.size()) {
                res.setLotId(campaignLots.get(i).getId());
                res.setStatus(AllocationResult.Status.PROVISOIRE);
                res.setJustification("Rang " + (i + 1) + " du tirage au sort");
            } else {
                res.setStatus(AllocationResult.Status.LISTE_ATTENTE);
                res.setJustification("Liste d'attente, rang " + (i + 1));
            }
            res.setDecidedBy(user.userId());
            results.save(res);
        }
        results.flush();
        c.setPublicEntropy(entropy);
        c.setSeedRevealed(true);
        c.setExecutedAt(Instant.now(clock));
        c.setExecutedBy(user.userId());
        c.setStatus(CampaignStatus.EXECUTEE);
        audit.record(AuditCategory.ATTRIBUTION, "TIRAGE_EXECUTE", "AllocationCampaign", id, Map.of(
                "algorithme", c.getAlgorithmVersion(), "graine", c.getSeedSecret(), "engagement", c.getSeedCommitment(),
                "entropie_publique", entropy, "ordre", String.join(",", order), "lots", campaignLots.size()));
        generateMinutesInternal(c, user);
        return c;
    }

    /** Décision motivée d'un membre de la commission sur une candidature (méthode COMMISSION). */
    @Transactional
    public AllocationResult commissionDecide(Long id, CommissionDecisionRequest r, CurrentUser user) {
        AllocationCampaign c = lock(id);
        if (c.getMethod() != AllocationMethod.COMMISSION) {
            throw BusinessException.rule("METHODE", "Cette procédure relève d'un tirage au sort");
        }
        requireStatus(c, EnumSet.of(CampaignStatus.VALIDEE), "Les décisions ne sont possibles qu'après validation de la liste");
        requireCommissionMember(c, user);
        requireNoConflict(c, user);
        AllocationCandidate cand = candidates.findById(r.candidateId())
                .filter(x -> x.getCampaignId().equals(id) && x.getEligibilityStatus() == AllocationCandidate.Eligibility.ELIGIBLE
                        && x.getWithdrawnAt() == null)
                .orElseThrow(() -> BusinessException.rule("CANDIDAT_INVALIDE", "Candidat non éligible ou étranger à la procédure"));
        if (!approvals.findByCampaignIdAndStep(id, AllocationApproval.Step.DECISION_COMMISSION).isEmpty()) {
            throw BusinessException.rule("DECISIONS_EN_VALIDATION", "Les décisions sont en cours de validation et ne peuvent plus changer");
        }
        if (r.lotId() != null) {
            if (!c.getLotIds().contains(r.lotId())) {
                throw BusinessException.rule("LOT_HORS_PROCEDURE", "Ce lot ne fait pas partie de la procédure");
            }
            lots.lockById(r.lotId());
            if (results.existsByLotIdAndStatusIn(r.lotId(), ACTIVE)) {
                throw BusinessException.conflict("DOUBLE_ATTRIBUTION", "Ce lot fait déjà l'objet d'une décision active");
            }
        }
        results.findByCandidateIdAndStatusNot(cand.getId(), AllocationResult.Status.ANNULE).ifPresent(prev -> {
            prev.setStatus(AllocationResult.Status.ANNULE);
            prev.setCancelReason("Remplacée par une nouvelle décision de la commission avant validation");
            results.saveAndFlush(prev);
        });
        AllocationResult res = new AllocationResult();
        res.setCampaignId(id);
        res.setCandidateId(cand.getId());
        res.setLotId(r.lotId());
        res.setStatus(r.lotId() == null ? AllocationResult.Status.LISTE_ATTENTE : AllocationResult.Status.PROVISOIRE);
        res.setJustification(r.justification());
        res.setDecidedBy(user.userId());
        res = results.save(res);
        audit.record(AuditCategory.ATTRIBUTION, "DECISION_COMMISSION", "AllocationResult", res.getId(), Map.of(
                "candidat", cand.getId(), "lot", String.valueOf(r.lotId()), "justification", r.justification()));
        return res;
    }

    // ================================================================== procès-verbal

    @Transactional
    public AllocationMinutes generateMinutes(Long id, CurrentUser user) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.EXECUTEE), "Le procès-verbal se génère après l'exécution");
        return generateMinutesInternal(c, user);
    }

    private AllocationMinutes generateMinutesInternal(AllocationCampaign c, CurrentUser user) {
        List<AllocationMinutes> existing = minutes.findByCampaignIdOrderByVersionNoAsc(c.getId());
        existing.stream().filter(m -> m.getStatus() == AllocationMinutes.Status.BROUILLON)
                .forEach(m -> m.setStatus(AllocationMinutes.Status.REMPLACE));
        int version = existing.size() + 1;
        byte[] pdf = minutesPdf.generate(buildMinutesData(c, version));
        StoredDocument doc = documents.store(new DocumentService.NewDocument(
                "PV " + c.getReference() + " — version " + version, DocumentCategory.PV_ATTRIBUTION,
                DocumentVisibility.BUREAU, null, "AllocationCampaign", c.getId(),
                c.getReference() + "-PV-v" + version + ".pdf", "application/pdf", pdf, true, false));
        AllocationMinutes m = new AllocationMinutes();
        m.setCampaignId(c.getId());
        m.setVersionNo(version);
        m.setDocumentId(doc.getId());
        m.setStatus(AllocationMinutes.Status.BROUILLON);
        m.setGeneratedBy(user.userId());
        m.setGeneratedAt(Instant.now(clock));
        m = minutes.save(m);
        audit.record(AuditCategory.ATTRIBUTION, "PV_GENERE", "AllocationCampaign", c.getId(),
                Map.of("version", version, "document", doc.getReference(), "sha256", doc.getSha256()));
        return m;
    }

    /** Validation du PV : résultats définitifs, lots attribués, lots non attribués libérés, bénéficiaires notifiés. */
    private void finalizeMinutes(AllocationCampaign c) {
        AllocationMinutes m = minutes.findTopByCampaignIdOrderByVersionNoDesc(c.getId()).orElseThrow();
        m.setStatus(AllocationMinutes.Status.VALIDE);
        m.setValidatedAt(Instant.now(clock));
        Set<Long> attributed = new HashSet<>();
        for (AllocationResult r : results.findByCampaignIdAndStatusIn(c.getId(), EnumSet.of(AllocationResult.Status.PROVISOIRE))) {
            PropertyLot lot = lots.lockById(r.getLotId()).orElseThrow();
            r.setStatus(AllocationResult.Status.DEFINITIF);
            property.transitionFromProcedure(lot, LotStatus.ATTRIBUE, "Attribué par la procédure " + c.getReference()
                    + " (PV v" + m.getVersionNo() + " validé)", c.getId());
            attributed.add(lot.getId());
            AllocationCandidate cand = candidates.findById(r.getCandidateId()).orElseThrow();
            notifications.notifyMember(cand.getMemberId(), "RESULTAT_ATTRIBUTION", "Attribution : lot " + lot.getReference(),
                    "À l'issue de la procédure « " + c.getTitle() + " », le lot " + lot.getReference()
                            + " vous est attribué. Le procès-verbal a été validé.",
                    "ATT_RESULTAT:" + c.getId() + ":" + cand.getId(), "AllocationCampaign", c.getId());
        }
        for (PropertyLot lot : lots.lockByIds(c.getLotIds())) {
            if (!attributed.contains(lot.getId()) && lot.getStatus() == LotStatus.RESERVE) {
                property.transitionFromProcedure(lot, LotStatus.DISPONIBLE, "Non attribué par la procédure " + c.getReference(), c.getId());
            }
        }
        for (AllocationResult r : results.findByCampaignIdAndStatusIn(c.getId(), EnumSet.of(AllocationResult.Status.LISTE_ATTENTE))) {
            AllocationCandidate cand = candidates.findById(r.getCandidateId()).orElseThrow();
            notifications.notifyMember(cand.getMemberId(), "RESULTAT_ATTRIBUTION", "Procédure " + c.getReference() + " : liste d'attente",
                    "Vous figurez sur la liste d'attente de la procédure « " + c.getTitle() + " »"
                            + (r.getDrawRank() == null ? "." : " (rang " + r.getDrawRank() + ")."),
                    "ATT_RESULTAT:" + c.getId() + ":" + cand.getId(), "AllocationCampaign", c.getId());
        }
        c.setStatus(CampaignStatus.PV_VALIDE);
        audit.record(AuditCategory.ATTRIBUTION, "PV_VALIDE", "AllocationCampaign", c.getId(),
                Map.of("version", m.getVersionNo(), "lots_attribues", attributed.size()));
    }

    @Transactional
    public AllocationCampaign publishResults(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.PV_VALIDE), "Le procès-verbal doit être validé avant la publication");
        c.setStatus(CampaignStatus.RESULTATS_PUBLIES);
        c.setResultsPublishedAt(Instant.now(clock));
        audit.record(AuditCategory.ATTRIBUTION, "RESULTATS_PUBLIES", "AllocationCampaign", id, Map.of());
        return c;
    }

    // ================================================================== contestations et corrections

    @Transactional
    public AllocationContest fileContest(Long id, Long memberId, ContestRequest r) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.LISTE_PUBLIEE, CampaignStatus.VALIDEE, CampaignStatus.EXECUTEE,
                CampaignStatus.PV_VALIDE, CampaignStatus.RESULTATS_PUBLIES), "Aucune contestation n'est recevable à ce stade");
        candidates.findByCampaignIdAndMemberId(id, memberId)
                .orElseThrow(() -> BusinessException.forbidden("Seuls les candidats peuvent contester cette procédure"));
        Long lotId = null;
        if (r.resultId() != null) {
            AllocationResult res = results.findById(r.resultId()).filter(x -> x.getCampaignId().equals(id))
                    .orElseThrow(() -> BusinessException.notFound("Résultat"));
            lotId = res.getLotId();
        }
        AllocationContest ct = new AllocationContest();
        ct.setReference(references.next("CONT"));
        ct.setCampaignId(id);
        ct.setMemberId(memberId);
        ct.setResultId(r.resultId());
        ct.setLotId(lotId);
        ct.setReason(r.reason());
        ct = contests.save(ct);
        audit.record(AuditCategory.ATTRIBUTION, "CONTESTATION_DEPOSEE", "AllocationContest", ct.getId(),
                Map.of("procedure", c.getReference(), "resultat", String.valueOf(r.resultId())));
        return ct;
    }

    @Transactional
    public AllocationContest decideContest(Long contestId, ContestDecisionRequest r, CurrentUser user) {
        AllocationContest ct = contests.findById(contestId).orElseThrow(() -> BusinessException.notFound("Contestation"));
        if (ct.getStatus() == AllocationContest.Status.REJETEE || ct.getStatus() == AllocationContest.Status.ACCEPTEE) {
            throw BusinessException.conflict("DEJA_TRANCHEE", "Cette contestation a déjà été tranchée");
        }
        if (user.memberId() != null && user.memberId().equals(ct.getMemberId())) {
            throw BusinessException.forbidden("Le demandeur ne peut pas statuer sur sa propre contestation");
        }
        AllocationCampaign c = lock(ct.getCampaignId());
        requireNoConflict(c, user);
        ct.setStatus(r.accept() ? AllocationContest.Status.ACCEPTEE : AllocationContest.Status.REJETEE);
        ct.setDecision(r.decision());
        ct.setDecidedBy(user.userId());
        ct.setDecidedAt(Instant.now(clock));
        if (r.accept() && ct.getLotId() != null) {
            PropertyLot lot = lots.lockById(ct.getLotId()).orElseThrow();
            if (lot.getStatus() == LotStatus.ATTRIBUE) {
                property.transitionFromProcedure(lot, LotStatus.LITIGIEUX, "Contestation " + ct.getReference() + " acceptée", c.getId());
            }
        }
        audit.record(AuditCategory.ATTRIBUTION, r.accept() ? "CONTESTATION_ACCEPTEE" : "CONTESTATION_REJETEE",
                "AllocationContest", ct.getId(), Map.of("decision", r.decision()));
        notifications.notifyMember(ct.getMemberId(), "CONTESTATION_DECISION", "Décision sur votre contestation " + ct.getReference(),
                (r.accept() ? "Votre contestation est acceptée. " : "Votre contestation est rejetée. ") + "Motif : " + r.decision(),
                "CONTESTATION:" + ct.getId(), "AllocationContest", ct.getId());
        return ct;
    }

    /**
     * Annulation formelle d'un résultat : demande motivée puis validations distinctes (étape ANNULATION_RESULTAT).
     * À la dernière validation, le résultat passe à ANNULE et le lot redevient disponible.
     */
    @Transactional
    public AllocationResult requestResultCancellation(Long resultId, String reason, CurrentUser user) {
        AllocationResult res = results.findById(resultId).orElseThrow(() -> BusinessException.notFound("Résultat"));
        if (!ACTIVE.contains(res.getStatus())) {
            throw BusinessException.rule("ETAT_INVALIDE", "Seul un résultat actif peut être annulé");
        }
        AllocationCampaign c = lock(res.getCampaignId());
        requireStatus(c, EnumSet.of(CampaignStatus.EXECUTEE, CampaignStatus.PV_VALIDE, CampaignStatus.RESULTATS_PUBLIES),
                "Annulation impossible à ce stade");
        requireNoConflict(c, user);
        recordApproval(c, AllocationApproval.Step.ANNULATION_RESULTAT, user, AllocationApproval.Decision.APPROUVE, reason, resultId);
        long count = approvedCount(c.getId(), AllocationApproval.Step.ANNULATION_RESULTAT, resultId);
        if (count >= c.getRequiredApprovals()) {
            res.setStatus(AllocationResult.Status.ANNULE);
            res.setCancelReason(reason);
            if (res.getLotId() != null) {
                PropertyLot lot = lots.lockById(res.getLotId()).orElseThrow();
                if (lot.getStatus() == LotStatus.ATTRIBUE || lot.getStatus() == LotStatus.LITIGIEUX || lot.getStatus() == LotStatus.RESERVE) {
                    property.transitionFromProcedure(lot, LotStatus.DISPONIBLE,
                            "Résultat annulé formellement (procédure " + c.getReference() + ") : " + reason, c.getId());
                }
            }
            audit.record(AuditCategory.ATTRIBUTION, "RESULTAT_ANNULE", "AllocationResult", resultId,
                    Map.of("motif", reason, "validations", count));
        }
        return res;
    }

    @Transactional
    public AllocationCampaign archive(Long id) {
        AllocationCampaign c = lock(id);
        requireStatus(c, EnumSet.of(CampaignStatus.RESULTATS_PUBLIES), "Seule une procédure publiée peut être archivée");
        if (contests.countByCampaignIdAndStatusIn(id, EnumSet.of(AllocationContest.Status.DEPOSEE, AllocationContest.Status.EN_EXAMEN)) > 0) {
            throw BusinessException.rule("CONTESTATIONS_OUVERTES", "Des contestations sont encore en cours");
        }
        c.setStatus(CampaignStatus.ARCHIVEE);
        c.setArchivedAt(Instant.now(clock));
        audit.record(AuditCategory.ATTRIBUTION, "PROCEDURE_ARCHIVEE", "AllocationCampaign", id, Map.of());
        return c;
    }

    @Transactional
    public AllocationCampaign cancel(Long id, String reason) {
        AllocationCampaign c = lock(id);
        requireStatus(c, CANCELLABLE, "Après l'exécution, seule l'annulation formelle des résultats est possible");
        c.setStatus(CampaignStatus.ANNULEE);
        c.setCancelReason(reason);
        for (PropertyLot l : lots.lockByIds(c.getLotIds())) {
            if (l.getStatus() == LotStatus.RESERVE) {
                property.transitionFromProcedure(l, LotStatus.DISPONIBLE, "Procédure " + c.getReference() + " annulée", id);
            }
        }
        audit.record(AuditCategory.ATTRIBUTION, "PROCEDURE_ANNULEE", "AllocationCampaign", id, Map.of("motif", reason));
        return c;
    }

    // ================================================================== vérification

    /** Recalcule intégralement le tirage à partir des données enregistrées et le compare aux résultats. */
    @Transactional(readOnly = true)
    public DrawVerification verify(Long id) {
        AllocationCampaign c = get(id);
        if (c.getMethod() != AllocationMethod.TIRAGE_AU_SORT || !c.isSeedRevealed()) {
            return new DrawVerification(false, c.getAlgorithmVersion(), false, false, false, false, List.of(), List.of(),
                    "Aucun tirage exécuté à vérifier");
        }
        List<String> numbers = memberNumbers(eligibleCandidates(id));
        List<String> lotRefs = lotReferences(lots.findAllById(c.getLotIds()));
        boolean commitment = DrawEngine.commitment(c.getSeedSecret()).equals(c.getSeedCommitment());
        boolean participants = DrawEngine.participantsDigest(c.getReference(), numbers).equals(c.getParticipantsDigest());
        boolean lotsOk = DrawEngine.lotsDigest(c.getReference(), lotRefs).equals(c.getLotsDigest());
        List<String> recomputed = DrawEngine.shuffle(numbers, DrawEngine.finalSeed(c.getSeedSecret(),
                c.getParticipantsDigest(), c.getLotsDigest(), c.getPublicEntropy()));
        Map<Long, String> numberByCandidate = new HashMap<>();
        for (AllocationCandidate cand : candidates.findByCampaignIdOrderByIdAsc(id)) {
            numberByCandidate.put(cand.getId(), members.findById(cand.getMemberId()).orElseThrow().getMemberNumber());
        }
        List<String> recorded = results.findByCampaignIdOrderByDrawRankAscIdAsc(id).stream()
                .filter(r -> r.getDrawRank() != null)
                .sorted(Comparator.comparing(AllocationResult::getDrawRank))
                .map(r -> numberByCandidate.get(r.getCandidateId()))
                .distinct().toList();
        boolean match = recomputed.equals(recorded);
        boolean valid = commitment && participants && lotsOk && match;
        return new DrawVerification(valid, c.getAlgorithmVersion(), commitment, participants, lotsOk, match, recomputed,
                recorded, valid ? "Le tirage recalculé est identique au tirage enregistré"
                : "Écart détecté : le tirage enregistré ne correspond pas au recalcul");
    }

    // ================================================================== consultation

    @Transactional(readOnly = true)
    public AllocationCampaign get(Long id) {
        return campaigns.findById(id).orElseThrow(() -> BusinessException.notFound("Procédure d'attribution"));
    }

    @Transactional(readOnly = true)
    public List<AllocationCampaign> list() {
        return campaigns.findAllByOrderByCreatedAtDesc();
    }

    public CampaignResponse toResponse(AllocationCampaign c) {
        List<AllocationCandidate> all = candidates.findByCampaignIdOrderByIdAsc(c.getId());
        long active = all.stream().filter(x -> x.getWithdrawnAt() == null).count();
        long eligibleCount = all.stream().filter(x -> x.getWithdrawnAt() == null
                && x.getEligibilityStatus() == AllocationCandidate.Eligibility.ELIGIBLE).count();
        return new CampaignResponse(c.getId(), c.getReference(), c.getTitle(), c.getDescription(), c.getMethod(),
                c.getStatus(), c.getEligibilityText(), c.getRulesText(), c.getMinSeniorityMonths(), c.isRequireUpToDate(),
                c.getMaxArrearsAmount(), Arrays.asList(c.getAllowedMemberTypes().split(",")), c.getReferenceDate(),
                c.getApplicationOpensAt(), c.getApplicationClosesAt(), c.getRequiredApprovals(), c.getSessionDate(),
                c.getSessionLocation(), c.getCriteriaPublishedAt(), c.getListPublishedAt(), c.getFrozenAt(),
                c.getParticipantsDigest(), c.getLotsDigest(), c.getSeedCommitment(),
                c.isSeedRevealed() ? c.getSeedSecret() : null, c.getPublicEntropy(), c.getAlgorithmVersion(),
                c.getExecutedAt(), c.getResultsPublishedAt(), c.getCancelReason(), List.copyOf(c.getLotIds()), active,
                eligibleCount, c.isDemo(), c.getVersion());
    }

    public List<CandidateResponse> candidateResponses(Long id) {
        return candidates.findByCampaignIdOrderByIdAsc(id).stream().map(this::candidateResponse).toList();
    }

    public CandidateResponse candidateResponse(AllocationCandidate x) {
        Member m = members.findById(x.getMemberId()).orElseThrow();
        return new CandidateResponse(x.getId(), m.getId(), m.getMemberNumber(), m.fullName(), x.getAppliedAt(),
                x.getEligibilityStatus(), x.getEligibilityReason(), x.getCheckedAt(), x.getWithdrawnAt());
    }

    public List<ResultResponse> resultResponses(Long id) {
        return results.findByCampaignIdOrderByDrawRankAscIdAsc(id).stream().map(this::resultResponse).toList();
    }

    public ResultResponse resultResponse(AllocationResult r) {
        AllocationCandidate cand = candidates.findById(r.getCandidateId()).orElseThrow();
        Member m = members.findById(cand.getMemberId()).orElseThrow();
        String lotRef = r.getLotId() == null ? null : lots.findById(r.getLotId()).map(PropertyLot::getReference).orElse(null);
        return new ResultResponse(r.getId(), cand.getId(), m.getMemberNumber(), m.fullName(), r.getLotId(), lotRef,
                r.getDrawRank(), r.getJustification(), r.getStatus(), r.getCancelReason(), r.getCreatedAt());
    }

    public List<ApprovalResponse> approvalResponses(Long id) {
        return approvals.findByCampaignIdOrderByIdAsc(id).stream().map(a -> new ApprovalResponse(a.getId(), a.getStep(),
                a.getApproverId(), users.findById(a.getApproverId()).map(UserAccount::getDisplayName).orElse(null),
                a.getDecision(), a.getComment(), a.getTargetId(), a.getDecidedAt())).toList();
    }

    public List<PvResponse> minutesResponses(Long id) {
        return minutes.findByCampaignIdOrderByVersionNoAsc(id).stream().map(m -> new PvResponse(m.getId(),
                m.getVersionNo(), m.getDocumentId(), m.getStatus(), m.getGeneratedAt(), m.getValidatedAt())).toList();
    }

    public ContestResponse contestResponse(AllocationContest ct) {
        String number = members.findById(ct.getMemberId()).map(Member::getMemberNumber).orElse(null);
        return new ContestResponse(ct.getId(), ct.getReference(), ct.getCampaignId(), ct.getMemberId(), number,
                ct.getResultId(), ct.getLotId(), ct.getReason(), ct.getStatus(), ct.getDecision(), ct.getDecidedAt(),
                ct.getCreatedAt());
    }

    public List<ContestResponse> contestResponses(Long id) {
        return contests.findByCampaignIdOrderByIdAsc(id).stream().map(this::contestResponse).toList();
    }

    public List<CommissionMember> commissionOf(Long id) {
        return commission.findByCampaignId(id);
    }

    /** Vue d'un membre : seulement les procédures publiées, sa propre candidature et les résultats publiés (sans noms). */
    @Transactional(readOnly = true)
    public List<MemberCampaignView> memberView(Long memberId) {
        List<MemberCampaignView> views = new ArrayList<>();
        for (AllocationCampaign c : campaigns.findByStatusNotInOrderByCreatedAtDesc(EnumSet.of(CampaignStatus.BROUILLON))) {
            AllocationCandidate mine = candidates.findByCampaignIdAndMemberId(c.getId(), memberId).orElse(null);
            ResultResponse myResult = mine == null ? null : results.findByCandidateIdAndStatusNot(mine.getId(),
                    AllocationResult.Status.ANNULE).map(this::resultResponse).orElse(null);
            List<PublishedResult> published = List.of();
            boolean resultsPublic = resultsPublic(c);
            if (resultsPublic) {
                published = results.findByCampaignIdOrderByDrawRankAscIdAsc(c.getId()).stream()
                        .filter(r -> r.getStatus() != AllocationResult.Status.ANNULE)
                        .map(r -> {
                            ResultResponse rr = resultResponse(r);
                            return new PublishedResult(rr.memberNumber(), rr.lotReference(), rr.drawRank(), rr.status());
                        }).toList();
            }
            views.add(new MemberCampaignView(c.getId(), c.getReference(), c.getTitle(), c.getMethod(), c.getStatus(),
                    c.getEligibilityText(), c.getRulesText(), c.getReferenceDate(), c.getApplicationClosesAt(),
                    c.getSeedCommitment(), c.getParticipantsDigest(), c.getLotsDigest(), c.getAlgorithmVersion(),
                    resultsPublic && c.isSeedRevealed() ? c.getSeedSecret() : null,
                    resultsPublic ? c.getPublicEntropy() : null, mine != null && mine.getWithdrawnAt() == null,
                    mine == null ? null : candidateResponse(mine), myResult, published));
        }
        return views;
    }

    /** Comptes pouvant siéger en commission : responsables actifs, sans exposer leurs coordonnées. */
    @Transactional(readOnly = true)
    public List<CommissionCandidate> commissionCandidates() {
        return users.findActiveByRoles(List.of("PRESIDENT", "TRESORIER", "SECRETAIRE", "AUDITEUR", "AGENT_DE_GESTION", "MEMBRE"))
                .stream()
                .map(u -> new CommissionCandidate(u.getId(), u.getDisplayName(), u.getRoles().stream().sorted().toList()))
                .sorted(Comparator.comparing(CommissionCandidate::displayName))
                .toList();
    }

    /**
     * Vérification ouverte aux membres une fois les résultats publiés : elle ne manipule que des numéros de membre
     * et des références de lot, déjà publiés.
     */
    @Transactional(readOnly = true)
    public DrawVerification verifyForMember(Long id) {
        AllocationCampaign c = get(id);
        if (!resultsPublic(c)) {
            throw BusinessException.rule("RESULTATS_NON_PUBLIES", "La vérification sera possible après publication des résultats");
        }
        return verify(id);
    }

    private static boolean resultsPublic(AllocationCampaign c) {
        return c.getStatus() == CampaignStatus.RESULTATS_PUBLIES || c.getStatus() == CampaignStatus.ARCHIVEE;
    }

    // ================================================================== outils internes

    private AllocationCampaign lock(Long id) {
        return campaigns.lockById(id).orElseThrow(() -> BusinessException.notFound("Procédure d'attribution"));
    }

    private static void requireStatus(AllocationCampaign c, Set<CampaignStatus> allowed, String message) {
        if (!allowed.contains(c.getStatus())) {
            throw BusinessException.rule("ETAPE_INVALIDE", message + " (état actuel : " + c.getStatus() + ")");
        }
    }

    private void requireCommissionMember(AllocationCampaign c, CurrentUser user) {
        CommissionMember cm = commission.findById(new CommissionMember.Key(c.getId(), user.userId()))
                .orElseThrow(() -> BusinessException.forbidden("Réservé aux membres de la commission"));
        if (cm.isConflictDeclared()) {
            throw BusinessException.forbidden("Conflit d'intérêts déclaré : vous ne pouvez pas participer à cette décision");
        }
    }

    /** Un responsable candidat à la procédure ne peut ni la valider, ni l'exécuter, ni statuer. */
    private void requireNoConflict(AllocationCampaign c, CurrentUser user) {
        if (user.memberId() != null && candidates.findByCampaignIdAndMemberId(c.getId(), user.memberId())
                .filter(x -> x.getWithdrawnAt() == null).isPresent()) {
            throw BusinessException.forbidden("Vous êtes candidat à cette procédure : conflit d'intérêts");
        }
    }

    private void recordApproval(AllocationCampaign c, AllocationApproval.Step step, CurrentUser user,
                                AllocationApproval.Decision decision, String comment, Long target) {
        boolean already = approvals.findByCampaignIdAndStepAndTargetId(c.getId(), step, target).stream()
                .anyMatch(a -> a.getApproverId().equals(user.userId()));
        if (already) {
            throw BusinessException.conflict("DEJA_VALIDE", "Vous vous êtes déjà prononcé sur cette étape");
        }
        AllocationApproval a = new AllocationApproval();
        a.setCampaignId(c.getId());
        a.setStep(step);
        a.setApproverId(user.userId());
        a.setDecision(decision);
        a.setComment(comment);
        a.setTargetId(target);
        a.setDecidedAt(Instant.now(clock));
        approvals.saveAndFlush(a);
        audit.record(AuditCategory.ATTRIBUTION, "VALIDATION_" + step.name(), "AllocationCampaign", c.getId(),
                Map.of("decision", decision.name(), "commentaire", String.valueOf(comment), "cible", String.valueOf(target)));
    }

    private long approvedCount(Long campaignId, AllocationApproval.Step step, Long target) {
        return approvals.findByCampaignIdAndStepAndTargetId(campaignId, step, target).stream()
                .filter(a -> a.getDecision() == AllocationApproval.Decision.APPROUVE)
                .map(AllocationApproval::getApproverId).distinct().count();
    }

    private List<AllocationCandidate> eligibleCandidates(Long campaignId) {
        return candidates.findByCampaignIdAndEligibilityStatusAndWithdrawnAtIsNull(campaignId,
                AllocationCandidate.Eligibility.ELIGIBLE);
    }

    private List<String> memberNumbers(List<AllocationCandidate> list) {
        return list.stream().map(x -> members.findById(x.getMemberId()).orElseThrow().getMemberNumber()).sorted().toList();
    }

    private static List<String> lotReferences(List<PropertyLot> list) {
        return list.stream().map(PropertyLot::getReference).sorted().toList();
    }

    private MinutesPdfGenerator.Data buildMinutesData(AllocationCampaign c, int version) {
        List<MinutesPdfGenerator.Person> officials = new ArrayList<>();
        Set<Long> userIds = new LinkedHashSet<>();
        if (c.getMethod() == AllocationMethod.COMMISSION) {
            for (CommissionMember cm : commission.findByCampaignId(c.getId())) {
                users.findById(cm.getUserId()).ifPresent(u -> officials.add(new MinutesPdfGenerator.Person(u.getDisplayName(),
                        (cm.getRoleInCommission() == null ? "Membre de la commission" : cm.getRoleInCommission())
                                + (cm.isConflictDeclared() ? " (conflit d'intérêts déclaré, ne participe pas)" : ""))));
                userIds.add(cm.getUserId());
            }
        }
        approvals.findByCampaignIdOrderByIdAsc(c.getId()).forEach(a -> userIds.add(a.getApproverId()));
        if (c.getExecutedBy() != null) {
            userIds.add(c.getExecutedBy());
        }
        for (Long uid : userIds) {
            users.findById(uid).filter(u -> officials.stream().noneMatch(p -> p.name().equals(u.getDisplayName())))
                    .ifPresent(u -> officials.add(new MinutesPdfGenerator.Person(u.getDisplayName(), roleLabel(u))));
        }
        List<String> lotLines = lots.findAllById(c.getLotIds()).stream()
                .sorted(Comparator.comparing(PropertyLot::getReference))
                .map(l -> l.getReference() + " (" + l.getLotType() + ", " + l.getAreaM2() + " m², " + l.getCommune() + ")")
                .toList();
        List<MinutesPdfGenerator.CandidateLine> cands = candidates.findByCampaignIdOrderByIdAsc(c.getId()).stream()
                .filter(x -> x.getWithdrawnAt() == null)
                .map(x -> {
                    Member m = members.findById(x.getMemberId()).orElseThrow();
                    return new MinutesPdfGenerator.CandidateLine(m.getMemberNumber(), m.fullName(),
                            x.getEligibilityStatus().name(), x.getEligibilityReason());
                })
                .sorted(Comparator.comparing(MinutesPdfGenerator.CandidateLine::memberNumber)).toList();
        List<MinutesPdfGenerator.ResultLine> res = resultResponses(c.getId()).stream()
                .filter(r -> r.status() != AllocationResult.Status.ANNULE)
                .map(r -> new MinutesPdfGenerator.ResultLine(r.drawRank(), r.memberNumber(), r.memberName(),
                        r.lotReference() == null ? "—" : r.lotReference(), r.status().name(), r.justification()))
                .toList();
        List<String> approvalLines = approvalResponses(c.getId()).stream()
                .map(a -> a.step() + " — " + a.approverName() + " — " + a.decision() + " — " + sn.coophabitat.reporting.PdfSupport.formatDate(a.decidedAt())
                        + (a.comment() == null || a.comment().isBlank() ? "" : " — " + a.comment()))
                .toList();
        List<String> contestLines = contests.findByCampaignIdOrderByIdAsc(c.getId()).stream()
                .map(ct -> ct.getReference() + " — " + ct.getStatus() + " — " + ct.getReason()).toList();
        return new MinutesPdfGenerator.Data(settings.current(), c, version, Instant.now(clock), officials, lotLines,
                cands, res, approvalLines, contestLines);
    }

    private static String roleLabel(UserAccount u) {
        return u.getRoles().stream().filter(r -> !r.equals("MEMBRE")).map(r -> switch (r) {
            case "PRESIDENT" -> "Président(e)";
            case "TRESORIER" -> "Trésorier(ère)";
            case "SECRETAIRE" -> "Secrétaire";
            case "AUDITEUR" -> "Auditeur";
            default -> r;
        }).collect(Collectors.joining(", "));
    }

    private void apply(AllocationCampaign c, CampaignRequest r) {
        for (String t : r.allowedMemberTypes()) {
            MemberType.valueOf(t);
        }
        c.setTitle(r.title());
        c.setDescription(r.description());
        c.setMethod(r.method());
        c.setEligibilityText(r.eligibilityText());
        c.setRulesText(r.rulesText());
        c.setMinSeniorityMonths(r.minSeniorityMonths());
        c.setRequireUpToDate(r.requireUpToDate());
        c.setMaxArrearsAmount(Money.requireWholeXof(r.maxArrearsAmount(), "Le plafond d'arriérés"));
        c.setAllowedMemberTypes(String.join(",", r.allowedMemberTypes()));
        c.setReferenceDate(r.referenceDate());
        c.setApplicationClosesAt(r.applicationClosesAt());
        c.setRequiredApprovals(r.requiredApprovals());
        c.setSessionDate(r.sessionDate());
        c.setSessionLocation(r.sessionLocation());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
