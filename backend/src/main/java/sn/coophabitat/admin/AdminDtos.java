package sn.coophabitat.admin;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record UserCreateRequest(@NotBlank @Email @Size(max = 200) String email,
                                    @NotBlank @Size(max = 200) String displayName,
                                    @NotEmpty Set<String> roles, Long memberId) {
    }

    public record RolesRequest(@NotEmpty Set<String> roles) {
    }

    public record EnabledRequest(boolean enabled, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record UserResponse(Long id, String email, String displayName, boolean enabled, boolean activated,
                               Set<String> roles, Long memberId, String memberNumber, String memberStatus,
                               Instant lastLoginAt, Instant lockedUntil, boolean demo, Instant createdAt) {
    }

    /** Compte créé ; access.link n'est renseigné que si l'invitation n'a pas pu partir par courriel. */
    public record CreatedUserResponse(UserResponse user, sn.coophabitat.security.AuthService.AccessLink access) {
    }

    /** Titulaire d'une fonction du bureau, avec l'état de son adhésion (null s'il n'est pas rattaché à un membre). */
    public record BureauHolder(Long userId, String displayName, String email, boolean activated, String memberStatus) {
    }

    /**
     * État de la mise en route : qui occupe les fonctions du bureau, si un responsable peut déjà valider les adhésions,
     * s'il existe un plan de cotisation actif et combien de demandes attendent.
     */
    public record BureauSetup(List<BureauHolder> presidents, List<BureauHolder> treasurers, List<BureauHolder> secretaries,
                              boolean approverInPlace, long activePlans, long pendingApplications) {
    }

    public record RoleResponse(String code, String label, String description, boolean technical, List<String> permissions) {
    }

    public record SettingsRequest(@NotBlank @Size(max = 200) String name, @Size(max = 200) String legalForm,
                                  @Size(max = 100) String registrationNumber, @Size(max = 300) String address,
                                  @Size(max = 100) String city, @Size(max = 40) String phone,
                                  @Email @Size(max = 200) String email, boolean publishPublicStats) {
    }

    public record DataRequestCreate(@NotNull DataSubjectRequest.Type type, @NotBlank @Size(min = 10, max = 4000) String details) {
    }

    public record DataRequestDecision(@NotNull DataSubjectRequest.Status status, @NotBlank @Size(min = 10, max = 4000) String response) {
    }

    public record DataRequestResponse(Long id, String reference, Long memberId, String memberNumber,
                                      DataSubjectRequest.Type type, String details, DataSubjectRequest.Status status,
                                      String response, Instant createdAt, Instant dueAt, Instant handledAt) {
    }
}
