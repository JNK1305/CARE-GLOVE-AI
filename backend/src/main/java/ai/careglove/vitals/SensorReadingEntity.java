package ai.careglove.vitals;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sensor_readings",
        uniqueConstraints = @UniqueConstraint(name = "uq_reading_patient_time", columnNames = {"patient_id", "timestamp"}),
        indexes = {
                @Index(name = "idx_reading_patient_time", columnList = "patient_id,timestamp"),
                @Index(name = "idx_reading_device_time", columnList = "device_id,timestamp")
        })
@Getter
@Setter
@NoArgsConstructor
public class SensorReadingEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(name = "device_id", nullable = false, length = 80)
    private String deviceId;
    @Column(nullable = false, columnDefinition = "datetime(6)")
    private Instant timestamp;
    @Column(name = "heart_rate")
    private Double heartRate;
    private Double gsr;
    private Double temperature;
    @Column(nullable = false, length = 24)
    private String state = "PENDING";
    @Column(nullable = false, length = 24)
    private String source;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();
    @Column(name = "signal_quality")
    private Double signalQuality;
    @Column(name = "firmware_version", length = 64)
    private String firmwareVersion;
}
