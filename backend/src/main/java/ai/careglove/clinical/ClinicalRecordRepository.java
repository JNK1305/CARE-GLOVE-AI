package ai.careglove.clinical;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicalRecordRepository extends JpaRepository<ClinicalRecordEntity, String> {
    List<ClinicalRecordEntity> findByPatientIdOrderByTimestampDesc(String patientId);
    List<ClinicalRecordEntity> findByPatientIdAndHospitalOrderByTimestampDesc(String patientId, String hospital);
    java.util.Optional<ClinicalRecordEntity> findByIdAndPatientId(String id, String patientId);
}
