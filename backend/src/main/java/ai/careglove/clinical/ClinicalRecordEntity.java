package ai.careglove.clinical;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "clinical_records", indexes = @Index(name = "idx_clinical_patient_time", columnList = "patient_id,timestamp"))
@Getter
@Setter
@NoArgsConstructor
public class ClinicalRecordEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;
    @Column(name = "actor_name", nullable = false, length = 160)
    private String actorName;
    @Column(name = "actor_role", nullable = false, length = 16)
    private String actorRole;
    @Column(nullable = false, length = 32)
    private String type;
    @Column(columnDefinition = "text")
    private String content;
    @Column(name = "medicine_name", length = 160)
    private String medicineName;
    @Column(length = 120)
    private String dosage;
    @Column(length = 120)
    private String frequency;
    @Column(columnDefinition = "text")
    private String instruction;
    @Column(name = "test_name", length = 160)
    private String testName;
    @Column(columnDefinition = "text")
    private String result;
    @Column(columnDefinition = "text")
    private String notes;
    @Column(nullable = false, length = 120)
    private String hospital = "CARE Hospital";
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "is_internal", nullable = false)
    private boolean isInternal = true;
    @Column(nullable = false)
    private Instant timestamp;
}
