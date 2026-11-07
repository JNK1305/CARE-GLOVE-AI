package ai.careglove.alerts;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {
    List<NotificationEntity> findByPatientIdInOrderByTimestampDesc(List<String> patientIds);
}
