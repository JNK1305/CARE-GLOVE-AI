package ai.careglove.patient;

import ai.careglove.common.Role;
import ai.careglove.user.UserEntity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PatientAccessService {
    private final PatientRepository patients;

    public PatientAccessService(PatientRepository patients) {
        this.patients = patients;
    }

    public PatientEntity getVisible(String id, UserEntity user) {
        PatientEntity patient = patients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found."));
        if (!patient.canBeViewedBy(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access to this patient is not authorized.");
        }
        return patient;
    }

    public List<PatientEntity> visibleTo(UserEntity user) {
        if (user.getRole() == Role.admin) {
            if (user.getHospital() != null && !user.getHospital().isBlank()) {
                return patients.findByHospitalOrderByNameAsc(user.getHospital());
            }
            return patients.findAllByOrderByNameAsc();
        }
        if (user.getRole() == Role.senior) return patients.findByDoctorIdAndLifecycleNotOrderByNameAsc(user.getId(), "DISCHARGED");
        return patients.findByAuthorizedTrueAndLifecycleNotOrderByNameAsc("DISCHARGED");
    }
}
