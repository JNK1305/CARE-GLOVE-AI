package ai.careglove.auth;

import ai.careglove.audit.AuditService;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtService tokens, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Transactional
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        UserEntity user = users.findByEmailIgnoreCase(request.email().trim())
                .filter(UserEntity::isActive)
                .filter(candidate -> passwords.matches(request.password(), candidate.getPasswordHash()))
                .filter(candidate -> request.role() == null || request.role() == candidate.getRole())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials."));
        audit.record(user.getId(), "LOGIN", "USER", user.getId(), Map.of());
        return new AuthDtos.LoginResponse(tokens.issue(user), response(user));
    }

    @Transactional
    public void logout(UserEntity user) {
        UserEntity current = users.findById(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is no longer valid."));
        current.setTokenVersion(current.getTokenVersion() + 1);
        audit.record(current.getId(), "LOGOUT", "USER", current.getId(), Map.of());
    }

    public AuthDtos.UserResponse current(UserEntity user) {
        return response(user);
    }

    static AuthDtos.UserResponse response(UserEntity user) {
        return new AuthDtos.UserResponse(user.getId(), user.getName(), user.getRole());
    }
}
