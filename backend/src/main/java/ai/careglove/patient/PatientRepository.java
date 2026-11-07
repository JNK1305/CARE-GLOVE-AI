package ai.careglove.patient;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<PatientEntity, String> {
    List<PatientEntity> findAllByOrderByNameAsc();
    List<PatientEntity> findByHospitalOrderByNameAsc(String hospital);
    List<PatientEntity> findByHospitalAndLifecycleNotOrderByNameAsc(String hospital, String lifecycle);
    List<PatientEntity> findByLifecycleNotOrderByNameAsc(String lifecycle);
    List<PatientEntity> findByDoctorIdAndLifecycleNotOrderByNameAsc(String doctorId, String lifecycle);
    List<PatientEntity> findByAuthorizedTrueAndLifecycleNotOrderByNameAsc(String lifecycle);
    Optional<PatientEntity> findByWardAndBedAndLifecycleNot(String ward, String bed, String lifecycle);
}
