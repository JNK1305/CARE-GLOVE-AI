package ai.careglove.vitals;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringSessionRepository extends JpaRepository<MonitoringSessionEntity, String> {
    Optional<MonitoringSessionEntity> findFirstByPatientIdAndStatusOrderByStartedAtDesc(String patientId, String status);
}
