package ai.careglove.user;

import ai.careglove.common.Role;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {
    @Id @Column(length = 64)
    private String id;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(nullable = false, length = 120)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Role role;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;
    @Column(nullable = false, length = 120)
    private String hospital = "CARE Hospital";
    @Column(length = 120)
    private String specialty;
    @Column(length = 40)
    private String contact;
    @Column(name = "employee_id", length = 64)
    private String employeeId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
