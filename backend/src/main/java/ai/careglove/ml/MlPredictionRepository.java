package ai.careglove.ml;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlPredictionRepository extends JpaRepository<MlPredictionEntity, String> {
    Optional<MlPredictionEntity> findByReadingId(String readingId);
    List<MlPredictionEntity> findByPatientIdOrderByTimestampDesc(String patientId);
}
