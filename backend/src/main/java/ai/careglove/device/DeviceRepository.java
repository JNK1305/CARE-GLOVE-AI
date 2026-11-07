package ai.careglove.device;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {
    List<DeviceEntity> findAllByOrderByIdAsc();
}
