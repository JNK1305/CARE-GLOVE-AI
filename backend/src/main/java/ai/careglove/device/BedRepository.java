package ai.careglove.device;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BedRepository extends JpaRepository<BedEntity, String> {
    List<BedEntity> findByWardIdOrderByLabelAsc(String wardId);
}
