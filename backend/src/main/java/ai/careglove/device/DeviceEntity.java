package ai.careglove.device;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class DeviceEntity {
    @Id @Column(length = 80)
    private String id;
    @Column(name = "patient_id", length = 64)
    private String patientId;
    @Column(nullable = false, length = 40)
    private String ward;
    @Column(nullable = false, length = 16)
    private String bed;
    @Column(nullable = false, length = 24)
    private String status = "ASSIGNED";
    @Column(name = "maintenance_note", length = 500)
    private String maintenanceNote;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
