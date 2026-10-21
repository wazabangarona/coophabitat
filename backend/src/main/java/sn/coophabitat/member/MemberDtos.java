package sn.coophabitat.member;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;

public final class MemberDtos {

    private MemberDtos() {
    }

    public static final String PHONE_PATTERN = "^\\+?[0-9 ]{9,20}$";

    public record CreateMemberRequest(@NotBlank @Size(max = 100) String firstName,
                                      @NotBlank @Size(max = 100) String lastName,
                                      @Email @Size(max = 200) String email,
                                      @Pattern(regexp = PHONE_PATTERN, message = "Numéro de téléphone invalide") String phone,
                                      @Size(max = 300) String address,
                                      @Size(max = 100) String city,
                                      @NotNull @PastOrPresent LocalDate membershipDate,
                                      @NotNull MemberType memberType,
                                      Long contributionPlanId,
                                      @Size(max = 1000) String notes,
                                      boolean createAccount) {
    }

    public record UpdateMemberRequest(@NotBlank @Size(max = 100) String firstName,
                                      @NotBlank @Size(max = 100) String lastName,
                                      @Email @Size(max = 200) String email,
                                      @Pattern(regexp = PHONE_PATTERN, message = "Numéro de téléphone invalide") String phone,
                                      @Size(max = 300) String address,
                                      @Size(max = 100) String city,
                                      @NotNull MemberType memberType,
                                      @Size(max = 1000) String notes,
                                      long version) {
    }

    public record ContactUpdateRequest(@Email @Size(max = 200) String email,
                                       @Pattern(regexp = PHONE_PATTERN, message = "Numéro de téléphone invalide") String phone,
                                       @Size(max = 300) String address,
                                       @Size(max = 100) String city) {
    }

    public record StatusChangeRequest(@NotNull MemberStatus status, @NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    public record AssignPlanRequest(@NotNull Long planId) {
    }

    public record BulkPlanRequest(@NotEmpty @Size(max = 500) java.util.List<@NotNull Long> memberIds, @NotNull Long planId) {
    }

    /** Résultat d'une affectation groupée : modifiés, déjà sur ce plan, exclus (radiés, demandes refusées, son propre dossier). */
    public record BulkPlanResult(int updated, int unchanged, int excluded) {
    }

    /** Approbation d'une demande : plan à affecter (facultatif si la trésorerie en a déjà affecté un) et commentaire. */
    public record ApprovalRequest(Long planId, @Size(max = 1000) String comment) {
    }

    public record RejectionRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    /** Demande d'adhésion à examiner. {@code self} : la demande est celle de l'utilisateur connecté. */
    public record ApplicationItem(Long id, String memberNumber, String firstName, String lastName, String email,
                                  String phone, String city, MemberType memberType, LocalDate membershipDate,
                                  Instant createdAt, Long contributionPlanId, String planName, boolean hasAccount,
                                  boolean online, boolean self) {
        public static ApplicationItem of(Member m, String planName, boolean hasAccount, boolean online, boolean self) {
            return new ApplicationItem(m.getId(), m.getMemberNumber(), m.getFirstName(), m.getLastName(), m.getEmail(),
                    m.getPhone(), m.getCity(), m.getMemberType(), m.getMembershipDate(), m.getCreatedAt(),
                    m.getContributionPlanId(), planName, hasAccount, online, self);
        }
    }

    /**
     * File des demandes. {@code canDecide} : l'utilisateur peut approuver ou refuser ; {@code bootstrap} : aucun autre
     * responsable habilité n'est encore en place (mise en route), l'utilisateur peut donc valider sa propre adhésion.
     */
    public record ApplicationQueue(java.util.List<ApplicationItem> items, boolean canDecide, boolean bootstrap) {
    }

    public record MemberResponse(Long id, String memberNumber, String firstName, String lastName, String email,
                                 String phone, String address, String city, LocalDate membershipDate,
                                 MemberType memberType, MemberStatus status, Long contributionPlanId, String notes,
                                 boolean demo, boolean hasAccount, Instant createdAt, Instant updatedAt, long version) {
        public static MemberResponse of(Member m, boolean hasAccount) {
            return new MemberResponse(m.getId(), m.getMemberNumber(), m.getFirstName(), m.getLastName(), m.getEmail(),
                    m.getPhone(), m.getAddress(), m.getCity(), m.getMembershipDate(), m.getMemberType(), m.getStatus(),
                    m.getContributionPlanId(), m.getNotes(), m.isDemo(), hasAccount, m.getCreatedAt(), m.getUpdatedAt(),
                    m.getVersion());
        }
    }

    public record MemberListItem(Long id, String memberNumber, String fullName, String city, MemberType memberType,
                                 MemberStatus status, LocalDate membershipDate, Long contributionPlanId, boolean demo) {
        public static MemberListItem of(Member m) {
            return new MemberListItem(m.getId(), m.getMemberNumber(), m.fullName(), m.getCity(), m.getMemberType(),
                    m.getStatus(), m.getMembershipDate(), m.getContributionPlanId(), m.isDemo());
        }
    }

    public record StatusHistoryItem(Long id, MemberStatus fromStatus, MemberStatus toStatus, String reason,
                                    Long changedBy, Instant changedAt) {
        public static StatusHistoryItem of(MembershipStatusHistory h) {
            return new StatusHistoryItem(h.getId(), h.getFromStatus(), h.getToStatus(), h.getReason(), h.getChangedBy(),
                    h.getChangedAt());
        }
    }

    /** activationLink n'est renseigné que si l'invitation n'a pas pu partir par courriel (aucun serveur SMTP). */
    public record CreatedMember(MemberResponse member, boolean invitationSent, String activationLink) {
    }
}
