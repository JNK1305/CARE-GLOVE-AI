package ai.careglove.patient;

import ai.careglove.common.Role;
import ai.careglove.user.UserEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "patients", indexes = @Index(name = "idx_patient_doctor_lifecycle", columnList = "doctor_id,lifecycle"))
@Getter
@Setter
@NoArgsConstructor
public class PatientEntity {
    @Id @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(nullable = false, length = 32)
    private String age;
    @Column(nullable = false, length = 40)
    private String ward;
    @Column(nullable = false, length = 16)
    private String bed;
    @Column(name = "bed_id", nullable = false, length = 80)
    private String bedId;
    @Column(nullable = false, length = 80)
    private String glove;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private UserEntity doctor;
    @Column(nullable = false, length = 16)
    private String status;
    private Double bpm;
    private Double temp;
    private Double gsr;
    @Column(nullable = false)
    private boolean authorized;
    @Column(length = 24)
    private String gender;
    @Column(length = 40)
    private String contact;
    @Column(name = "emergency_contact", length = 40)
    private String emergencyContact;
    @Column(name = "blood_group", length = 8)
    private String bloodGroup;
    @Column(name = "clinical_condition", length = 500)
    private String condition;
    @Column(name = "admission_date", length = 32)
    private String admissionDate;
    @Column(name = "admission_time", length = 16)
    private String admissionTime;
    @Column(nullable = false, length = 16)
    private String lifecycle = "ADMITTED";
    @Column(name = "monitoring_status", nullable = false, length = 32)
    private String monitoringStatus = "READY";
    @Column(nullable = false, length = 120)
    private String hospital = "CARE Hospital";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public boolean canBeViewedBy(UserEntity user) {
        if (user.getHospital() != null && hospital != null && !user.getHospital().equalsIgnoreCase(hospital)) {
            return false;
        }
        if (user.getRole() == Role.admin) return true;
        if (user.getRole() == Role.senior) return doctor.getId().equals(user.getId()) && !"DISCHARGED".equals(lifecycle);
        return authorized && !"DISCHARGED".equals(lifecycle);
    }
}
