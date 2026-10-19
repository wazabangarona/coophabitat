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
                               Set<String> roles, Long memberId, Instant lastLoginAt, Instant lockedUntil,
                               boolean demo, Instant createdAt) {
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
