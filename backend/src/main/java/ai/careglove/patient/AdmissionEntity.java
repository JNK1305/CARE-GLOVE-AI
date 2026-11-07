package ai.careglove.patient;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "admissions")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionEntity {
    @Id @Column(length = 36)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private PatientEntity patient;
    @Column(name = "admission_date", length = 32)
    private String admissionDate;
    @Column(name = "admission_time", length = 16)
    private String admissionTime;
    @Column(name = "clinical_condition", length = 500)
    private String condition;
    @Column(nullable = false, length = 16)
    private String lifecycle;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
