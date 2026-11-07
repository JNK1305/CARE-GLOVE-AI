package ai.careglove.device;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "beds", uniqueConstraints = @UniqueConstraint(name = "uq_bed_ward_label", columnNames = {"ward_id", "label"}))
@Getter
@Setter
@NoArgsConstructor
public class BedEntity {
    @Id @Column(length = 80)
    private String id;
    @Column(name = "ward_id", nullable = false, length = 40)
    private String wardId;
    @Column(nullable = false, length = 16)
    private String label;
    @Column(name = "glove_id", nullable = false, unique = true, length = 80)
    private String gloveId;
}
