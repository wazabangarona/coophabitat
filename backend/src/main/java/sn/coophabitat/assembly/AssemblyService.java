package sn.coophabitat.assembly;

import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.assembly.AssemblyDtos.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.document.*;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.reporting.PdfSupport;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Assemblées générales : programmation, ordre du jour, convocation, présences, résolutions et comptes rendus.
 * Le vote électronique n'est pas implémenté : les résultats des votes tenus en séance sont saisis par le secrétariat.
 */
@Service
public class AssemblyService {

    private static final EnumSet<GeneralAssembly.Status> VISIBLE_TO_MEMBERS = EnumSet.of(GeneralAssembly.Status.CONVOQUEE,
            GeneralAssembly.Status.TENUE, GeneralAssembly.Status.CLOTUREE, GeneralAssembly.Status.ANNULEE);

    private final GeneralAssemblyRepository assemblies;
    private final AgendaItemRepository agenda;
    private final ResolutionRepository resolutions;
    private final AttendanceRepository attendance;
    private final AssemblyMinutesRepository minutes;
    private final MemberRepository members;
    private final DocumentService documents;
    private final DocumentRepository documentRepository;
    private final NotificationService notifications;
    private final CoopSettingsRepository settings;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final Clock clock;

    public AssemblyService(GeneralAssemblyRepository assemblies, AgendaItemRepository agenda,
                           ResolutionRepository resolutions, AttendanceRepository attendance,
                           AssemblyMinutesRepository minutes, MemberRepository members, DocumentService documents,
                           DocumentRepository documentRepository, NotificationService notifications,
                           CoopSettingsRepository settings, ReferenceGenerator references, AuditService audit,
                           Clock clock) {
        this.assemblies = assemblies;
        this.agenda = agenda;
        this.resolutions = resolutions;
        this.attendance = attendance;
        this.minutes = minutes;
        this.members = members;
        this.documents = documents;
        this.documentRepository = documentRepository;
        this.notifications = notifications;
        this.settings = settings;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GeneralAssembly create(AssemblyRequest r, CurrentUser user) {
        GeneralAssembly a = new GeneralAssembly();
        a.setReference(references.next("AG"));
        a.setCreatedBy(user.userId());
        fill(a, r);
        a = assemblies.save(a);
        audit.record(AuditCategory.ASSEMBLEE, "AG_PROGRAMMEE", "GeneralAssembly", a.getId(),
                Map.of("reference", a.getReference(), "date", a.getScheduledAt().toString()));
        return a;
    }

    @Transactional
    public GeneralAssembly update(Long id, AssemblyRequest r) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.PROGRAMMEE), "Une assemblée convoquée n'est plus modifiable ; annulez-la et reprogrammez-la");
        fill(a, r);
        audit.record(AuditCategory.ASSEMBLEE, "AG_MODIFIEE", "GeneralAssembly", id, Map.of());
        return a;
    }

    @Transactional
    public List<AgendaItem> setAgenda(Long id, List<AgendaEntry> items) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.PROGRAMMEE), "L'ordre du jour est figé après la convocation");
        agenda.deleteAll(agenda.findByAssemblyIdOrderByPositionAsc(id));
        agenda.flush();
        List<AgendaItem> saved = new ArrayList<>();
        int pos = 1;
        for (AgendaEntry e : items) {
            AgendaItem it = new AgendaItem();
            it.setAssemblyId(id);
            it.setPosition(pos++);
            it.setTitle(e.title());
            it.setDescription(e.description());
            saved.add(agenda.save(it));
        }
        audit.record(AuditCategory.ASSEMBLEE, "ORDRE_DU_JOUR", "GeneralAssembly", id, Map.of("points", items.size()));
        return saved;
    }

    /** Convocation : PDF publié aux membres et notification de chaque membre actif ou suspendu. */
    @Transactional
    public GeneralAssembly convene(Long id) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.PROGRAMMEE), "Assemblée déjà convoquée ou close");
        List<AgendaItem> items = agenda.findByAssemblyIdOrderByPositionAsc(id);
        if (items.isEmpty()) {
            throw BusinessException.rule("ORDRE_DU_JOUR_VIDE", "Définissez l'ordre du jour avant de convoquer");
        }
        if (a.getScheduledAt().isBefore(Instant.now(clock))) {
            throw BusinessException.rule("DATE_PASSEE", "La date de l'assemblée est passée");
        }
        byte[] pdf = convocationPdf(a, items);
        documents.store(new DocumentService.NewDocument("Convocation — " + a.getTitle(), DocumentCategory.CONVOCATION,
                DocumentVisibility.MEMBRES, null, "GeneralAssembly", id, a.getReference() + "-convocation.pdf",
                "application/pdf", pdf, true, true));
        a.setStatus(GeneralAssembly.Status.CONVOQUEE);
        a.setConvenedAt(Instant.now(clock));
        a.setUpdatedAt(Instant.now(clock));
        audit.record(AuditCategory.ASSEMBLEE, "AG_CONVOQUEE", "GeneralAssembly", id, Map.of());
        String when = PdfSupport.formatDate(a.getScheduledAt());
        for (Member m : members.findByStatusIn(List.of(MemberStatus.ACTIF, MemberStatus.SUSPENDU))) {
            notifications.notifyMember(m.getId(), "CONVOCATION_AG", "Convocation : " + a.getTitle(),
                    "Vous êtes convoqué(e) à l'assemblée générale « " + a.getTitle() + " » le " + when
                            + (a.getLocation() == null ? "" : " — " + a.getLocation())
                            + ". La convocation et l'ordre du jour sont dans votre espace membre.",
                    "CONVOCATION:" + id + ":" + m.getId(), "GeneralAssembly", id);
        }
        return a;
    }

    @Transactional
    public List<Attendance> recordAttendance(Long id, List<AttendanceEntry> entries, CurrentUser user) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.CONVOQUEE, GeneralAssembly.Status.TENUE), "Présences saisissables le jour de l'assemblée");
        List<Attendance> saved = new ArrayList<>();
        for (AttendanceEntry e : entries) {
            members.findById(e.memberId()).orElseThrow(() -> BusinessException.notFound("Membre " + e.memberId()));
            if (e.status() == Attendance.Status.REPRESENTE) {
                if (e.proxyMemberId() == null || e.proxyMemberId().equals(e.memberId())) {
                    throw BusinessException.badRequest("MANDATAIRE", "Un membre représenté doit désigner un autre membre mandataire");
                }
                members.findById(e.proxyMemberId()).orElseThrow(() -> BusinessException.notFound("Mandataire"));
            }
            Attendance at = attendance.findById(new Attendance.Key(id, e.memberId())).orElseGet(Attendance::new);
            at.setAssemblyId(id);
            at.setMemberId(e.memberId());
            at.setStatus(e.status());
            at.setProxyMemberId(e.status() == Attendance.Status.REPRESENTE ? e.proxyMemberId() : null);
            at.setRecordedBy(user.userId());
            at.setRecordedAt(Instant.now(clock));
            saved.add(attendance.save(at));
        }
        audit.record(AuditCategory.ASSEMBLEE, "PRESENCES_SAISIES", "GeneralAssembly", id, Map.of("lignes", entries.size()));
        return saved;
    }

    @Transactional
    public GeneralAssembly markHeld(Long id) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.CONVOQUEE), "L'assemblée doit être convoquée");
        if (java.time.LocalDate.now(clock).isBefore(a.getScheduledAt().atZone(clock.getZone()).toLocalDate())) {
            throw BusinessException.rule("AVANT_LA_DATE", "L'assemblée ne peut pas être déclarée tenue avant sa date");
        }
        a.setStatus(GeneralAssembly.Status.TENUE);
        a.setHeldAt(Instant.now(clock));
        a.setUpdatedAt(Instant.now(clock));
        QuorumInfo q = quorum(id);
        audit.record(AuditCategory.ASSEMBLEE, "AG_TENUE", "GeneralAssembly", id,
                Map.of("presents", q.present(), "representes", q.represented(), "membres_actifs", q.activeMembers()));
        return a;
    }

    @Transactional
    public Resolution recordResolution(Long id, ResolutionRequest r, CurrentUser user) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.TENUE), "Les résolutions se saisissent pour une assemblée tenue et non clôturée");
        if (resolutions.existsByAssemblyIdAndNumber(id, r.number())) {
            throw BusinessException.conflict("NUMERO_EXISTANT", "Une résolution porte déjà ce numéro");
        }
        if (r.agendaItemId() != null && agenda.findById(r.agendaItemId()).filter(x -> x.getAssemblyId().equals(id)).isEmpty()) {
            throw BusinessException.badRequest("POINT_INCONNU", "Point de l'ordre du jour inconnu");
        }
        Resolution res = new Resolution();
        res.setAssemblyId(id);
        res.setAgendaItemId(r.agendaItemId());
        res.setNumber(r.number());
        res.setText(r.text());
        res.setVotesFor(r.votesFor());
        res.setVotesAgainst(r.votesAgainst());
        res.setAbstentions(r.abstentions());
        res.setOutcome(r.outcome());
        res.setRecordedBy(user.userId());
        res.setRecordedAt(Instant.now(clock));
        res = resolutions.save(res);
        audit.record(AuditCategory.ASSEMBLEE, "RESOLUTION_ENREGISTREE", "Resolution", res.getId(),
                Map.of("numero", r.number(), "issue", r.outcome().name()));
        return res;
    }

    @Transactional
    public AssemblyMinutes draftMinutes(Long id, String content, CurrentUser user) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.TENUE, GeneralAssembly.Status.CLOTUREE), "Le compte rendu suit la tenue de l'assemblée");
        List<AssemblyMinutes> versions = minutes.findByAssemblyIdOrderByVersionNoAsc(id);
        versions.stream().filter(m -> m.getStatus() == AssemblyMinutes.Status.BROUILLON)
                .forEach(m -> m.setStatus(AssemblyMinutes.Status.REMPLACE));
        AssemblyMinutes m = new AssemblyMinutes();
        m.setAssemblyId(id);
        m.setVersionNo(versions.size() + 1);
        m.setContent(content);
        m.setAuthorId(user.userId());
        m.setCreatedAt(Instant.now(clock));
        m = minutes.save(m);
        audit.record(AuditCategory.ASSEMBLEE, "COMPTE_RENDU_REDIGE", "GeneralAssembly", id, Map.of("version", m.getVersionNo()));
        return m;
    }

    /** Publication d'une version : PDF aux membres ; la version publiée précédente est marquée remplacée (et reste consultable par le bureau). */
    @Transactional
    public AssemblyMinutes publishMinutes(Long id, Long minutesId) {
        GeneralAssembly a = lock(id);
        AssemblyMinutes m = minutes.findById(minutesId).filter(x -> x.getAssemblyId().equals(id))
                .orElseThrow(() -> BusinessException.notFound("Compte rendu"));
        if (m.getStatus() != AssemblyMinutes.Status.BROUILLON) {
            throw BusinessException.rule("ETAPE_INVALIDE", "Seul un brouillon peut être publié");
        }
        minutes.findTopByAssemblyIdAndStatusOrderByVersionNoDesc(id, AssemblyMinutes.Status.PUBLIE).ifPresent(prev -> {
            prev.setStatus(AssemblyMinutes.Status.REMPLACE);
            if (prev.getDocumentId() != null) {
                documents.archive(prev.getDocumentId(), "Remplacé par la version " + m.getVersionNo());
            }
        });
        byte[] pdf = minutesPdf(a, m);
        StoredDocument doc = documents.store(new DocumentService.NewDocument(
                "Compte rendu — " + a.getTitle() + " (v" + m.getVersionNo() + ")", DocumentCategory.COMPTE_RENDU_AG,
                DocumentVisibility.MEMBRES, null, "GeneralAssembly", id,
                a.getReference() + "-compte-rendu-v" + m.getVersionNo() + ".pdf", "application/pdf", pdf, true, true));
        m.setStatus(AssemblyMinutes.Status.PUBLIE);
        m.setPublishedAt(Instant.now(clock));
        m.setDocumentId(doc.getId());
        audit.record(AuditCategory.ASSEMBLEE, "COMPTE_RENDU_PUBLIE", "GeneralAssembly", id,
                Map.of("version", m.getVersionNo(), "sha256", doc.getSha256()));
        for (Member mem : members.findByStatusIn(List.of(MemberStatus.ACTIF, MemberStatus.SUSPENDU))) {
            notifications.notifyMember(mem.getId(), "COMPTE_RENDU_AG", "Compte rendu publié : " + a.getTitle(),
                    "Le compte rendu de l'assemblée « " + a.getTitle() + " » (version " + m.getVersionNo()
                            + ") est consultable dans votre espace membre.",
                    "CR_AG:" + m.getId() + ":" + mem.getId(), "GeneralAssembly", id);
        }
        return m;
    }

    @Transactional
    public GeneralAssembly close(Long id) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.TENUE), "Seule une assemblée tenue peut être clôturée");
        if (minutes.findTopByAssemblyIdAndStatusOrderByVersionNoDesc(id, AssemblyMinutes.Status.PUBLIE).isEmpty()) {
            throw BusinessException.rule("COMPTE_RENDU_REQUIS", "Publiez le compte rendu avant de clôturer");
        }
        a.setStatus(GeneralAssembly.Status.CLOTUREE);
        a.setUpdatedAt(Instant.now(clock));
        audit.record(AuditCategory.ASSEMBLEE, "AG_CLOTUREE", "GeneralAssembly", id, Map.of());
        return a;
    }

    @Transactional
    public GeneralAssembly cancel(Long id, String reason) {
        GeneralAssembly a = lock(id);
        require(a, EnumSet.of(GeneralAssembly.Status.PROGRAMMEE, GeneralAssembly.Status.CONVOQUEE), "Une assemblée tenue ne s'annule pas");
        boolean wasConvened = a.getStatus() == GeneralAssembly.Status.CONVOQUEE;
        a.setStatus(GeneralAssembly.Status.ANNULEE);
        a.setCancelReason(reason);
        a.setUpdatedAt(Instant.now(clock));
        audit.record(AuditCategory.ASSEMBLEE, "AG_ANNULEE", "GeneralAssembly", id, Map.of("motif", reason));
        if (wasConvened) {
            for (Member m : members.findByStatusIn(List.of(MemberStatus.ACTIF, MemberStatus.SUSPENDU))) {
                notifications.notifyMember(m.getId(), "ANNULATION_AG", "Assemblée annulée : " + a.getTitle(),
                        "L'assemblée générale « " + a.getTitle() + " » est annulée. Motif : " + reason,
                        "ANNULATION_AG:" + id + ":" + m.getId(), "GeneralAssembly", id);
            }
        }
        return a;
    }

    // ---------------------------------------------------------------- consultation

    @Transactional(readOnly = true)
    public List<GeneralAssembly> list(boolean bureau) {
        return bureau ? assemblies.findAllByOrderByScheduledAtDesc()
                : assemblies.findByStatusInOrderByScheduledAtDesc(VISIBLE_TO_MEMBERS);
    }

    @Transactional(readOnly = true)
    public GeneralAssembly get(Long id, boolean bureau) {
        GeneralAssembly a = assemblies.findById(id).orElseThrow(() -> BusinessException.notFound("Assemblée"));
        if (!bureau && !VISIBLE_TO_MEMBERS.contains(a.getStatus())) {
            throw BusinessException.notFound("Assemblée");
        }
        return a;
    }

    @Transactional(readOnly = true)
    public AssemblyResponse toResponse(GeneralAssembly a, boolean bureau) {
        List<AgendaItemResponse> items = agenda.findByAssemblyIdOrderByPositionAsc(a.getId()).stream()
                .map(i -> new AgendaItemResponse(i.getId(), i.getPosition(), i.getTitle(), i.getDescription())).toList();
        List<ResolutionResponse> res = resolutions.findByAssemblyIdOrderByIdAsc(a.getId()).stream()
                .map(r -> new ResolutionResponse(r.getId(), r.getAgendaItemId(), r.getNumber(), r.getText(), r.getVotesFor(),
                        r.getVotesAgainst(), r.getAbstentions(), r.getOutcome(), r.getRecordedAt())).toList();
        Long convocation = documentRepository.findByRelatedTypeAndRelatedIdOrderByIdDesc("GeneralAssembly", a.getId()).stream()
                .filter(d -> d.getCategory() == DocumentCategory.CONVOCATION).map(StoredDocument::getId).findFirst().orElse(null);
        Long published = minutes.findTopByAssemblyIdAndStatusOrderByVersionNoDesc(a.getId(), AssemblyMinutes.Status.PUBLIE)
                .map(AssemblyMinutes::getDocumentId).orElse(null);
        return new AssemblyResponse(a.getId(), a.getReference(), a.getTitle(), a.getAssemblyType(), a.getScheduledAt(),
                a.getLocation(), a.getModality(), a.getStatus(), a.getQuorumRule(), a.getConvocationText(), a.getConvenedAt(),
                a.getHeldAt(), a.getCancelReason(), items, res, convocation, published,
                bureau ? quorum(a.getId()) : null, a.isDemo(), a.getVersion());
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> attendance(Long id) {
        return attendance.findByAssemblyId(id).stream().map(at -> {
            Member m = members.findById(at.getMemberId()).orElseThrow();
            String proxy = at.getProxyMemberId() == null ? null
                    : members.findById(at.getProxyMemberId()).map(Member::getMemberNumber).orElse(null);
            return new AttendanceResponse(m.getId(), m.getMemberNumber(), m.fullName(), at.getStatus(), at.getProxyMemberId(),
                    proxy, at.getRecordedAt());
        }).sorted(Comparator.comparing(AttendanceResponse::memberNumber)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Attendance> myAttendance(Long id, Long memberId) {
        return attendance.findById(new Attendance.Key(id, memberId));
    }

    @Transactional(readOnly = true)
    public List<MinutesResponse> minutesOf(Long id, boolean bureau) {
        return minutes.findByAssemblyIdOrderByVersionNoAsc(id).stream()
                .filter(m -> bureau || m.getStatus() == AssemblyMinutes.Status.PUBLIE)
                .map(m -> new MinutesResponse(m.getId(), m.getVersionNo(), m.getStatus(), m.getContent(), m.getCreatedAt(),
                        m.getPublishedAt(), m.getDocumentId())).toList();
    }

    public QuorumInfo quorum(Long id) {
        long active = members.countByStatus(MemberStatus.ACTIF);
        List<Attendance> list = attendance.findByAssemblyId(id);
        long present = list.stream().filter(x -> x.getStatus() == Attendance.Status.PRESENT).count();
        long represented = list.stream().filter(x -> x.getStatus() == Attendance.Status.REPRESENTE).count();
        long absent = list.stream().filter(x -> x.getStatus() == Attendance.Status.ABSENT).count();
        long excused = list.stream().filter(x -> x.getStatus() == Attendance.Status.EXCUSE).count();
        Integer pct = active == 0 ? null : BigDecimal.valueOf((present + represented) * 100)
                .divide(BigDecimal.valueOf(active), 0, RoundingMode.HALF_UP).intValue();
        return new QuorumInfo(active, present, represented, absent, excused, pct);
    }

    // ---------------------------------------------------------------- outils

    private byte[] convocationPdf(GeneralAssembly a, List<AgendaItem> items) {
        return PdfSupport.build(settings.current(), "Convocation — assemblée générale "
                + (a.getAssemblyType() == GeneralAssembly.Type.ORDINAIRE ? "ordinaire" : "extraordinaire"), a.getReference(),
                Instant.now(clock), doc -> {
                    PdfPTable kv = PdfSupport.keyValueTable();
                    PdfSupport.row(kv, "Objet", a.getTitle());
                    PdfSupport.row(kv, "Date et heure", PdfSupport.formatDate(a.getScheduledAt()));
                    PdfSupport.row(kv, "Lieu", a.getLocation());
                    PdfSupport.row(kv, "Modalité", a.getModality().name());
                    PdfSupport.row(kv, "Règle de quorum", a.getQuorumRule());
                    doc.add(kv);
                    if (a.getConvocationText() != null && !a.getConvocationText().isBlank()) {
                        PdfSupport.paragraph(doc, a.getConvocationText());
                    }
                    PdfSupport.section(doc, "Ordre du jour");
                    for (AgendaItem i : items) {
                        PdfSupport.paragraph(doc, i.getPosition() + ". " + i.getTitle()
                                + (i.getDescription() == null || i.getDescription().isBlank() ? "" : " — " + i.getDescription()));
                    }
                    PdfSupport.note(doc, "Un membre empêché peut se faire représenter par un autre membre selon les règles statutaires. "
                            + "Document généré par CoopHabitat, non signé électroniquement.");
                });
    }

    private byte[] minutesPdf(GeneralAssembly a, AssemblyMinutes m) {
        QuorumInfo q = quorum(a.getId());
        return PdfSupport.build(settings.current(), "Compte rendu — " + a.getTitle(), a.getReference() + "-v" + m.getVersionNo(),
                Instant.now(clock), doc -> {
                    PdfPTable kv = PdfSupport.keyValueTable();
                    PdfSupport.row(kv, "Date de l'assemblée", PdfSupport.formatDate(a.getHeldAt() == null ? a.getScheduledAt() : a.getHeldAt()));
                    PdfSupport.row(kv, "Lieu", a.getLocation());
                    PdfSupport.row(kv, "Version", String.valueOf(m.getVersionNo()));
                    PdfSupport.row(kv, "Présents / représentés", q.present() + " / " + q.represented()
                            + " (membres actifs : " + q.activeMembers() + ")");
                    doc.add(kv);
                    PdfSupport.section(doc, "Compte rendu");
                    for (String para : m.getContent().split("\\n\\s*\\n")) {
                        PdfSupport.paragraph(doc, para.trim());
                    }
                    List<Resolution> res = resolutions.findByAssemblyIdOrderByIdAsc(a.getId());
                    if (!res.isEmpty()) {
                        PdfSupport.section(doc, "Résolutions");
                        PdfPTable t = PdfSupport.table(new float[]{10, 50, 10, 10, 10, 10}, "N°", "Texte", "Pour", "Contre", "Abst.", "Issue");
                        for (Resolution r : res) {
                            PdfSupport.cell(t, r.getNumber());
                            PdfSupport.cell(t, r.getText());
                            PdfSupport.cell(t, String.valueOf(r.getVotesFor() == null ? "—" : r.getVotesFor()));
                            PdfSupport.cell(t, String.valueOf(r.getVotesAgainst() == null ? "—" : r.getVotesAgainst()));
                            PdfSupport.cell(t, String.valueOf(r.getAbstentions() == null ? "—" : r.getAbstentions()));
                            PdfSupport.cell(t, r.getOutcome().name());
                        }
                        doc.add(t);
                    }
                    PdfSupport.note(doc, "Les décomptes de voix sont ceux constatés en séance et saisis par le secrétariat. "
                            + "Document non signé électroniquement : l'original signé reste conservé par le secrétariat.");
                });
    }

    private void fill(GeneralAssembly a, AssemblyRequest r) {
        a.setTitle(r.title());
        a.setAssemblyType(r.type());
        a.setScheduledAt(r.scheduledAt());
        a.setLocation(r.location());
        a.setModality(r.modality());
        a.setQuorumRule(r.quorumRule());
        a.setConvocationText(r.convocationText());
        a.setUpdatedAt(Instant.now(clock));
    }

    private GeneralAssembly lock(Long id) {
        return assemblies.lockById(id).orElseThrow(() -> BusinessException.notFound("Assemblée"));
    }

    private static void require(GeneralAssembly a, Set<GeneralAssembly.Status> allowed, String message) {
        if (!allowed.contains(a.getStatus())) {
            throw BusinessException.rule("ETAPE_INVALIDE", message + " (état actuel : " + a.getStatus() + ")");
        }
    }
}
