package ai.careglove.journey;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "patient_journey_events", indexes = @Index(name = "idx_journey_patient_time", columnList = "patient_id,timestamp"))
@Getter
@Setter
@NoArgsConstructor
public class PatientJourneyEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(nullable = false, length = 32)
    private String type;
    @Column(nullable = false, length = 160)
    private String actor;
    @Column(nullable = false, length = 500)
    private String description;
    @Column(nullable = false)
    private Instant timestamp;
    @Column(length = 16)
    private String severity;
    @Column(columnDefinition = "json")
    private String metadata;
}
