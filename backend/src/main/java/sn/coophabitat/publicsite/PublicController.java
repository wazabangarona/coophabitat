package sn.coophabitat.publicsite;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.coophabitat.admin.CoopSettings;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.RegistrationService;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.property.LotStatus;
import sn.coophabitat.property.PropertyLotRepository;
import sn.coophabitat.property.PropertyProjectRepository;

import java.util.*;

/** Informations publiques : aucune donnée personnelle ni financière individuelle. */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Site public")
public class PublicController {

    private final CoopSettingsRepository settings;
    private final MemberRepository members;
    private final PropertyLotRepository lots;
    private final PropertyProjectRepository projects;
    private final AppProperties props;
    private final RegistrationService registration;
    private final NotificationService notifications;

    public PublicController(CoopSettingsRepository settings, MemberRepository members, PropertyLotRepository lots,
                            PropertyProjectRepository projects, AppProperties props, RegistrationService registration,
                            NotificationService notifications) {
        this.registration = registration;
        this.notifications = notifications;
        this.settings = settings;
        this.members = members;
        this.lots = lots;
        this.projects = projects;
        this.props = props;
    }

    public record PublicInfo(String name, String legalForm, String registrationNumber, String address, String city,
                             String phone, String email, String escrowStatement, boolean publicStatsAvailable,
                             String environment, boolean paymentSimulationEnabled, boolean demoData,
                             boolean registrationOpen, boolean passwordResetByEmail) {
    }

    public record PublicProject(String code, String name, String region, String departement, String commune,
                                String status, long lots) {
    }

    public record PublicStats(long activeMembers, Map<String, Long> lotsByStatus, long projects, String note) {
    }

    @GetMapping("/info")
    @Transactional(readOnly = true)
    public PublicInfo info() {
        CoopSettings s = settings.current();
        return new PublicInfo(s.getName(), s.getLegalForm(), s.getRegistrationNumber(), s.getAddress(), s.getCity(),
                s.getPhone(), s.getEmail(), s.isEscrowIntegrated() ? s.getEscrowStatement()
                : "Fonds suivis comptablement — séquestre externe non vérifié ou non intégré",
                s.isPublishPublicStats(), props.environment().name(), props.payment().mockEnabled() && !props.isProduction(),
                s.isDemo(), registration.enabled(), notifications.emailAvailable());
    }

    @GetMapping("/projects")
    @Transactional(readOnly = true)
    public List<PublicProject> projects() {
        return projects.findAll().stream().sorted(Comparator.comparing(p -> p.getCode()))
                .map(p -> new PublicProject(p.getCode(), p.getName(), p.getRegion(), p.getDepartement(), p.getCommune(),
                        p.getStatus().name(), lots.countByProjectId(p.getId()))).toList();
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    @Operation(summary = "Statistiques agrégées, publiées uniquement si le bureau l'a autorisé")
    public PublicStats stats() {
        if (!settings.current().isPublishPublicStats()) {
            throw BusinessException.notFound("Statistiques publiques");
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (LotStatus s : LotStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        for (Object[] row : lots.countByStatusGrouped()) {
            byStatus.put(((LotStatus) row[0]).name(), (Long) row[1]);
        }
        return new PublicStats(members.countByStatus(MemberStatus.ACTIF), byStatus, projects.count(),
                "Chiffres calculés en temps réel à partir des données de la coopérative ; aucune donnée individuelle.");
    }
}
