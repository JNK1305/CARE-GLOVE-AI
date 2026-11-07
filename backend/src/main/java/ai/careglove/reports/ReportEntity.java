package ai.careglove.reports;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reports", indexes = @Index(name = "idx_report_patient_time", columnList = "patient_id,timestamp"))
@Getter
@Setter
@NoArgsConstructor
public class ReportEntity {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "patient_id", nullable = false, length = 64)
    private String patientId;
    @Column(name = "author_id", nullable = false, length = 64)
    private String authorId;
    @Column(name = "author_name", nullable = false, length = 160)
    private String authorName;
    @Column(nullable = false)
    private Instant timestamp;
    @Column(nullable = false, length = 240)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
}
