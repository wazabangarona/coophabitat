package sn.coophabitat.property;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.property.PropertyDtos.*;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PropertyService {

    private final PropertyProjectRepository projects;
    private final PropertyLotRepository lots;
    private final LotStatusHistoryRepository history;
    private final AuditService audit;
    private final Clock clock;

    public PropertyService(PropertyProjectRepository projects, PropertyLotRepository lots,
                           LotStatusHistoryRepository history, AuditService audit, Clock clock) {
        this.projects = projects;
        this.lots = lots;
        this.history = history;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PropertyProject> listProjects() {
        return projects.findAllByOrderByNameAsc();
    }

    public long lotCount(Long projectId) {
        return lots.countByProjectId(projectId);
    }

    @Transactional
    public PropertyProject createProject(ProjectRequest r) {
        if (projects.findByCode(r.code()).isPresent()) {
            throw BusinessException.conflict("CODE_EXISTANT", "Un programme porte déjà ce code");
        }
        PropertyProject p = new PropertyProject();
        applyProject(p, r);
        p = projects.save(p);
        audit.record(AuditCategory.LOT, "PROGRAMME_CREE", "PropertyProject", p.getId(), Map.of("code", p.getCode()));
        return p;
    }

    @Transactional
    public PropertyProject updateProject(Long id, ProjectRequest r) {
        PropertyProject p = projects.findById(id).orElseThrow(() -> BusinessException.notFound("Programme"));
        if (!p.getCode().equals(r.code()) && projects.findByCode(r.code()).isPresent()) {
            throw BusinessException.conflict("CODE_EXISTANT", "Un programme porte déjà ce code");
        }
        applyProject(p, r);
        audit.record(AuditCategory.LOT, "PROGRAMME_MODIFIE", "PropertyProject", id, Map.of("statut", r.status().name()));
        return p;
    }

    @Transactional(readOnly = true)
    public Page<PropertyLot> search(Long projectId, LotStatus status, LotType type, String q, Pageable pageable) {
        return lots.search(projectId, status, type, q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public PropertyLot get(Long id) {
        return lots.findById(id).orElseThrow(() -> BusinessException.notFound("Lot"));
    }

    @Transactional
    public PropertyLot createLot(LotRequest r) {
        if (lots.findByReference(r.reference()).isPresent()) {
            throw BusinessException.conflict("REFERENCE_EXISTANTE", "Un lot porte déjà cette référence");
        }
        projects.findById(r.projectId()).orElseThrow(() -> BusinessException.notFound("Programme"));
        PropertyLot l = new PropertyLot();
        applyLot(l, r);
        l.setStatus(LotStatus.EN_PREPARATION);
        l = lots.save(l);
        recordHistory(l.getId(), null, LotStatus.EN_PREPARATION, "Création du lot", null, null);
        audit.record(AuditCategory.LOT, "LOT_CREE", "PropertyLot", l.getId(), Map.of("reference", l.getReference()));
        return l;
    }

    @Transactional
    public PropertyLot updateLot(Long id, LotRequest r) {
        PropertyLot l = get(id);
        if (l.getStatus() == LotStatus.ATTRIBUE || l.getStatus() == LotStatus.RESERVE) {
            throw BusinessException.rule("LOT_ENGAGE", "Un lot réservé ou attribué ne peut pas être modifié");
        }
        if (!l.getReference().equals(r.reference()) && lots.findByReference(r.reference()).isPresent()) {
            throw BusinessException.conflict("REFERENCE_EXISTANTE", "Un lot porte déjà cette référence");
        }
        applyLot(l, r);
        audit.record(AuditCategory.LOT, "LOT_MODIFIE", "PropertyLot", id, Map.of("reference", l.getReference()));
        return l;
    }

    /** Changement manuel d'état, limité aux transitions autorisées (jamais vers RESERVE ou ATTRIBUE). */
    @Transactional
    public PropertyLot changeStatus(Long id, LotStatusRequest r) {
        PropertyLot l = lots.lockById(id).orElseThrow(() -> BusinessException.notFound("Lot"));
        if (LotStatus.PROCEDURE_ONLY.contains(r.status())) {
            throw BusinessException.rule("PROCEDURE_REQUISE",
                    "La réservation et l'attribution d'un lot ne se font que par une procédure d'attribution");
        }
        if (!l.getStatus().manualTargets().contains(r.status())) {
            throw BusinessException.rule("TRANSITION_INTERDITE",
                    "Passage de " + l.getStatus() + " à " + r.status() + " non autorisé");
        }
        transition(l, r.status(), r.reason(), null, null);
        return l;
    }

    /** Transition déclenchée par une procédure d'attribution (seul chemin vers RESERVE et ATTRIBUE). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionFromProcedure(PropertyLot l, LotStatus target, String reason, Long campaignId) {
        transition(l, target, reason, "AllocationCampaign", campaignId);
    }

    @Transactional(readOnly = true)
    public List<LotStatusHistory> history(Long lotId) {
        return history.findByLotIdOrderByChangedAtAscIdAsc(lotId);
    }

    public LotResponse toResponse(PropertyLot l) {
        String project = projects.findById(l.getProjectId()).map(PropertyProject::getName).orElse(null);
        return new LotResponse(l.getId(), l.getReference(), l.getProjectId(), project, l.getLotType(), l.getRegion(),
                l.getDepartement(), l.getCommune(), l.getLocality(), l.getAreaM2(), l.getLatitude(), l.getLongitude(),
                l.getCoordinatesSource(), l.isCoordinatesVerified(), l.getStatus(), l.getDescription(),
                l.getCreatedAt(), l.getUpdatedAt(), l.isDemo(), l.getVersion());
    }

    public LotPublicView toPublicView(PropertyLot l) {
        String project = projects.findById(l.getProjectId()).map(PropertyProject::getName).orElse(null);
        return new LotPublicView(l.getId(), l.getReference(), project, l.getLotType(), l.getCommune(), l.getAreaM2(),
                l.getStatus(), l.isDemo());
    }

    private void transition(PropertyLot l, LotStatus target, String reason, String sourceType, Long sourceId) {
        LotStatus from = l.getStatus();
        l.setStatus(target);
        recordHistory(l.getId(), from, target, reason, sourceType, sourceId);
        audit.record(AuditCategory.LOT, "STATUT_LOT_MODIFIE", "PropertyLot", l.getId(),
                Map.of("reference", l.getReference(), "de", from.name(), "vers", target.name(), "motif", reason,
                        "source", sourceType == null ? "manuel" : sourceType + "#" + sourceId));
    }

    private void recordHistory(Long lotId, LotStatus from, LotStatus to, String reason, String sourceType, Long sourceId) {
        LotStatusHistory h = new LotStatusHistory();
        h.setLotId(lotId);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setReason(reason);
        h.setSourceType(sourceType);
        h.setSourceId(sourceId);
        h.setChangedBy(CurrentUserProvider.current().map(CurrentUser::userId).orElse(null));
        h.setChangedAt(Instant.now(clock));
        history.save(h);
    }

    private void applyProject(PropertyProject p, ProjectRequest r) {
        p.setCode(r.code());
        p.setName(r.name());
        p.setDescription(r.description());
        p.setRegion(r.region());
        p.setDepartement(r.departement());
        p.setCommune(r.commune());
        p.setStatus(r.status());
    }

    private void applyLot(PropertyLot l, LotRequest r) {
        if ((r.latitude() == null) != (r.longitude() == null)) {
            throw BusinessException.badRequest("COORDONNEES_INCOMPLETES", "Latitude et longitude vont ensemble");
        }
        if (r.latitude() != null && (r.coordinatesSource() == null || r.coordinatesSource().isBlank())) {
            throw BusinessException.badRequest("SOURCE_REQUISE",
                    "Indiquez la source des coordonnées (relevé GPS, géomètre, document officiel…)");
        }
        l.setReference(r.reference());
        l.setProjectId(r.projectId());
        l.setLotType(r.lotType());
        l.setRegion(r.region());
        l.setDepartement(r.departement());
        l.setCommune(r.commune());
        l.setLocality(r.locality());
        l.setAreaM2(r.areaM2());
        l.setLatitude(r.latitude());
        l.setLongitude(r.longitude());
        l.setCoordinatesSource(r.latitude() == null ? null : r.coordinatesSource());
        l.setCoordinatesVerified(r.latitude() != null && r.coordinatesVerified());
        l.setDescription(r.description());
    }
}
