package sn.coophabitat.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 200) String email,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, MeResponse user) {
        TokenResponse withTtl(AuthController controller) {
            return new TokenResponse(accessToken, tokenType, controller.ttl(), user);
        }
    }

    public record MeResponse(Long id, String email, String displayName, Long memberId, Set<String> roles,
                             Set<String> permissions) {
        public static MeResponse of(CurrentUser u) {
            return new MeResponse(u.userId(), u.email(), u.displayName(), u.memberId(), u.roles(), u.permissions());
        }
    }

    public record PasswordResetRequest(@NotBlank @Email @Size(max = 200) String email) {
    }

    public record PasswordResetConfirm(@NotBlank @Size(max = 128) String token,
                                       @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    public record SessionInfo(Long id, String createdAt, String expiresAt, String userAgent, String ipAddress) {
    }
}
