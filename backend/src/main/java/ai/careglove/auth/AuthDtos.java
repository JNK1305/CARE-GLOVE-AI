package ai.careglove.auth;

import ai.careglove.common.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password,
            Role role) {}

    public record UserResponse(String id, String name, Role role) {}
    public record LoginResponse(String token, UserResponse user) {}
}
