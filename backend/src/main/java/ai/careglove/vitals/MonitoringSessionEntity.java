package ai.careglove.vitals;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "monitoring_sessions", indexes = @Index(name = "idx_monitoring_patient_status", columnList = "patient_id,status"))
@Getter
@Setter
@NoArgsConstructor
public class MonitoringSessionEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(name = "device_id", nullable = false, length = 80)
    private String deviceId;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(nullable = false)
    private boolean active = false;
    @Column(name = "persistence_started_at")
    private Instant persistenceStartedAt;
    @Column(name = "duration_seconds", nullable = false)
    private long durationSeconds = 0;
    @Column(name = "anomalous_window_count", nullable = false)
    private int anomalousWindowCount = 0;
    @Column(name = "normal_window_count", nullable = false)
    private int normalWindowCount = 0;
    @Column(name = "last_alert_at")
    private Instant lastAlertAt;
    @Column(name = "cooldown_until")
    private Instant cooldownUntil;
    @Column(name = "recovered_at")
    private Instant recoveredAt;
}
