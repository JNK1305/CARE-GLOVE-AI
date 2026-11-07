package ai.careglove.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientJourneyRepository extends JpaRepository<PatientJourneyEntity, String> {
    List<PatientJourneyEntity> findByPatientIdOrderByTimestampAsc(String patientId);
    List<PatientJourneyEntity> findByPatientIdOrderByTimestampDesc(String patientId);
}
