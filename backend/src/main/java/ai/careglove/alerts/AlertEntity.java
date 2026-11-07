package ai.careglove.alerts;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "alerts",
        indexes = {
                @Index(name = "idx_alert_patient_time", columnList = "patient_id,timestamp"),
                @Index(name = "idx_alert_status", columnList = "status,created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class AlertEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(name = "device_id", nullable = false, length = 80)
    private String deviceId;
    @Column(nullable = false)
    private Instant timestamp;
    @Column(nullable = false, length = 16)
    private String severity;
    @Column(name = "monitoring_state", nullable = false, length = 32)
    private String monitoringState;
    @Column(name = "trigger_source", nullable = false, length = 32)
    private String trigger;
    @Column(nullable = false, length = 64)
    private String source;
    @Column(name = "prediction_id", nullable = false, unique = true, length = 36)
    private String predictionId;
    @Column(nullable = false, length = 32)
    private String parameter;
    @Column(name = "reading_value", nullable = false)
    private double value;
    @Column
    private Double threshold;
    @Column(name = "affected_signals", nullable = false, columnDefinition = "json")
    private String affectedSignals = "[]";
    @Column(nullable = false, length = 1000)
    private String explanation;
    @Column(nullable = false, length = 16)
    private String status = "OPEN";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @Column(name = "acknowledged_by", length = 64)
    private String acknowledgedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "heart_rate")
    private Double heartRate;
    @Column(name = "gsr")
    private Double gsr;
    @Column(name = "temperature")
    private Double temperature;
    @Column(name = "model_version", length = 64)
    private String modelVersion;
    @Column(name = "model_score")
    private Double modelScore;
    @Column(name = "deduplication_key", length = 160)
    private String deduplicationKey;
}
