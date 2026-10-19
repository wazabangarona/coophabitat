package sn.coophabitat.property;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public final class PropertyDtos {

    private PropertyDtos() {
    }

    public record ProjectRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+") String code,
                                 @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
                                 @Size(max = 100) String region, @Size(max = 100) String departement,
                                 @Size(max = 100) String commune, @NotNull PropertyProject.Status status) {
    }

    public record ProjectResponse(Long id, String code, String name, String description, String region,
                                  String departement, String commune, PropertyProject.Status status, long lotCount,
                                  boolean demo) {
        public static ProjectResponse of(PropertyProject p, long lots) {
            return new ProjectResponse(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getRegion(),
                    p.getDepartement(), p.getCommune(), p.getStatus(), lots, p.isDemo());
        }
    }

    public record LotRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+") String reference,
                             @NotNull Long projectId, @NotNull LotType lotType,
                             @NotBlank @Size(max = 100) String region, @NotBlank @Size(max = 100) String departement,
                             @NotBlank @Size(max = 100) String commune, @Size(max = 200) String locality,
                             @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal areaM2,
                             @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                             @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                             @Size(max = 300) String coordinatesSource, boolean coordinatesVerified,
                             @Size(max = 2000) String description) {
    }

    public record LotStatusRequest(@NotNull LotStatus status, @NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    /** Vue complète, réservée aux rôles disposant de LOT_READ_ALL. */
    public record LotResponse(Long id, String reference, Long projectId, String projectName, LotType lotType,
                              String region, String departement, String commune, String locality, BigDecimal areaM2,
                              BigDecimal latitude, BigDecimal longitude, String coordinatesSource,
                              boolean coordinatesVerified, LotStatus status, String description, Instant createdAt,
                              Instant updatedAt, boolean demo, long version) {
    }

    /** Vue restreinte pour les membres : aucune coordonnée ni information interne. */
    public record LotPublicView(Long id, String reference, String projectName, LotType lotType, String commune,
                                BigDecimal areaM2, LotStatus status, boolean demo) {
    }

    public record LotHistoryItem(Long id, LotStatus fromStatus, LotStatus toStatus, String reason, String sourceType,
                                 Long sourceId, Long changedBy, Instant changedAt) {
        public static LotHistoryItem of(LotStatusHistory h) {
            return new LotHistoryItem(h.getId(), h.getFromStatus(), h.getToStatus(), h.getReason(), h.getSourceType(),
                    h.getSourceId(), h.getChangedBy(), h.getChangedAt());
        }
    }
}
