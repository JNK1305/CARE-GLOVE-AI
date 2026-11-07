package ai.careglove.alerts;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notification_patient_time", columnList = "patient_id,timestamp"))
@Getter
@Setter
@NoArgsConstructor
public class NotificationEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "alert_id", nullable = false, unique = true, length = 36)
    private String alertId;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(nullable = false, length = 16)
    private String severity;
    @Column(nullable = false, length = 32)
    private String parameter;
    @Column(name = "reading_value", nullable = false)
    private double value;
    @Column(nullable = false)
    private Instant timestamp;
    @Column(name = "is_read", nullable = false)
    private boolean read = false;
}
