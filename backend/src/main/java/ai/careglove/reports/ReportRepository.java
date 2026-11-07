package ai.careglove.reports;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<ReportEntity, String> {
    List<ReportEntity> findByPatientIdInOrderByTimestampDesc(List<String> patientIds);
}
