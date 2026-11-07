package ai.careglove.alerts;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<AlertEntity, String> {
    boolean existsByPredictionId(String predictionId);
    List<AlertEntity> findByPatientIdOrderByTimestampDesc(String patientId);
    List<AlertEntity> findByPatientIdInOrderByTimestampDesc(List<String> patientIds);
    Optional<AlertEntity> findFirstByDeduplicationKeyAndStatusInOrderByCreatedAtDesc(
            String deduplicationKey, List<String> statuses);
    Optional<AlertEntity> findFirstByPatientIdAndSeverityAndStatusInOrderByCreatedAtDesc(
            String patientId, String severity, List<String> statuses);
    Optional<AlertEntity> findFirstByPatientIdAndStatusInOrderByCreatedAtDesc(
            String patientId, List<String> statuses);
    List<AlertEntity> findByPatientIdAndStatusInOrderByCreatedAtDesc(
            String patientId, List<String> statuses);
    Optional<AlertEntity> findFirstByPatientIdAndParameterAndStatusInOrderByCreatedAtDesc(
            String patientId, String parameter, List<String> statuses);
    List<AlertEntity> findByPatientIdAndParameterAndStatusIn(
            String patientId, String parameter, List<String> statuses);
}
