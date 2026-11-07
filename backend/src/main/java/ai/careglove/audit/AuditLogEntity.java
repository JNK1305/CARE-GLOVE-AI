package ai.careglove.audit;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor_time", columnList = "actor_id,created_at"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditLogEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "actor_id", length = 64)
    private String actorId;
    @Column(nullable = false, length = 40)
    private String action;
    @Column(nullable = false, length = 40)
    private String resource;
    @Column(name = "resource_id", length = 128)
    private String resourceId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(columnDefinition = "json")
    private String metadata;
}
