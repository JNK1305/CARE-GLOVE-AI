package ai.careglove.auth;

import ai.careglove.audit.AuditService;
import ai.careglove.common.Role;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public UserAdminController(UserRepository users, PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    public record CreateUserRequest(
            @Size(max = 64) String id,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 160) String name,
            Role role,
            @NotBlank @Size(min = 12, max = 200) String password) {}

    @GetMapping
    public java.util.List<AuthDtos.UserResponse> list(@AuthenticationPrincipal UserEntity actor) {
        String hospital = actor.getHospital();
        java.util.List<UserEntity> userList;
        if (hospital != null && !hospital.isBlank()) {
            userList = users.findByHospitalAndActiveTrueOrderByNameAsc(hospital);
        } else {
            userList = users.findAll();
        }
        return userList.stream().map(AuthService::response).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.UserResponse create(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal UserEntity actor) {
        if (request.role() == null) throw new IllegalArgumentException("Role is required.");
        String id = request.id() == null || request.id().isBlank() ? UUID.randomUUID().toString() : request.id().trim();
        if (users.existsById(id) || users.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new IllegalArgumentException("User already exists.");
        }
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(request.email().trim().toLowerCase());
        user.setName(request.name().trim());
        user.setRole(request.role());
        user.setHospital(actor.getHospital() != null ? actor.getHospital() : "CARE Hospital");
        user.setPasswordHash(passwords.encode(request.password()));
        user = users.save(user);
        audit.record(actor.getId(), "USER_CREATE", "USER", user.getId(), Map.of("role", user.getRole().name()));
        return AuthService.response(user);
    }
}
