package ai.careglove.vitals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorReadingRepository extends JpaRepository<SensorReadingEntity, String> {
    List<SensorReadingEntity> findByPatientIdOrderByTimestampDesc(String patientId);
    Optional<SensorReadingEntity> findByPatientIdAndTimestamp(String patientId, Instant timestamp);
    Page<SensorReadingEntity> findByPatientIdAndStateOrderByTimestampAsc(
            String patientId, String state, Pageable pageable);
    Optional<SensorReadingEntity> findTopByPatientIdOrderByTimestampDesc(String patientId);
}
