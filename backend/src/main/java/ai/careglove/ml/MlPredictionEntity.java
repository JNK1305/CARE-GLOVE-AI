package ai.careglove.ml;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ml_predictions", indexes = @Index(name = "idx_prediction_patient_time", columnList = "patient_id,timestamp"))
@Getter
@Setter
@NoArgsConstructor
public class MlPredictionEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "reading_id", nullable = false, unique = true, length = 36)
    private String readingId;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(nullable = false)
    private Instant timestamp;
    @Column(nullable = false, length = 32)
    private String state;
    @Column(name = "anomaly_score")
    private Double anomalyScore;
    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;
    @Column(nullable = false, columnDefinition = "json")
    private String contributors = "[]";
    @Column(name = "data_quality_valid", nullable = false)
    private boolean dataQualityValid;
    @Column(name = "data_quality", nullable = false, columnDefinition = "json")
    private String dataQuality;
    @Column(name = "scored_window", nullable = false)
    private boolean scoredWindow;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
