package ai.careglove.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wards")
@Getter
@Setter
@NoArgsConstructor
public class WardEntity {
    @Id @Column(length = 40)
    private String id;
    @Column(nullable = false, length = 40)
    private String name;
}
