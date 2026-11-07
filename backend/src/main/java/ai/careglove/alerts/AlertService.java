package ai.careglove.alerts;

import ai.careglove.audit.AuditService;
import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.user.UserEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private final AlertRepository alerts;
    private final PatientAccessService patientAccess;
    private final AuditService audit;

    public AlertService(AlertRepository alerts, PatientAccessService patientAccess, AuditService audit) {
        this.alerts = alerts;
        this.patientAccess = patientAccess;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AlertDtos.AlertResponse> forPatient(String patientId, UserEntity user) {
        patientAccess.getVisible(patientId, user);
        return alerts.findByPatientIdOrderByTimestampDesc(patientId).stream().map(AlertDtos.AlertResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertDtos.AlertResponse> list(UserEntity user) {
        List<String> patientIds = patientAccess.visibleTo(user).stream().map(PatientEntity::getId).toList();
        return patientIds.isEmpty() ? List.of()
                : alerts.findByPatientIdInOrderByTimestampDesc(patientIds).stream().map(AlertDtos.AlertResponse::from).toList();
    }

    @Transactional
    public AlertDtos.AlertResponse update(String alertId, String status, UserEntity actor) {
        AlertEntity alert = alerts.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found."));
        patientAccess.getVisible(alert.getPatientId(), actor);
        if (!List.of("OPEN", "ACKNOWLEDGED", "RESOLVED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be OPEN, ACKNOWLEDGED, or RESOLVED.");
        }
        alert.setStatus(status);
        if ("ACKNOWLEDGED".equals(status)) {
            alert.setAcknowledgedAt(Instant.now());
            alert.setAcknowledgedBy(actor.getId());
        } else if ("RESOLVED".equals(status)) {
            alert.setResolvedAt(Instant.now());
        }
        alert = alerts.save(alert);
        audit.record(actor.getId(), "ALERT_" + status, "ALERT", alertId, Map.of("status", status));
        return AlertDtos.AlertResponse.from(alert);
    }
}
