package ai.careglove.alerts;

import ai.careglove.audit.AuditService;
import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.user.UserEntity;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final PatientAccessService patientAccess;
    private final AuditService audit;

    public NotificationService(NotificationRepository notifications, PatientAccessService patientAccess, AuditService audit) {
        this.notifications = notifications;
        this.patientAccess = patientAccess;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<NotificationDtos.NotificationResponse> list(UserEntity user) {
        List<String> ids = patientAccess.visibleTo(user).stream().map(PatientEntity::getId).toList();
        return ids.isEmpty() ? List.of() : notifications.findByPatientIdInOrderByTimestampDesc(ids)
                .stream().map(NotificationDtos.NotificationResponse::from).toList();
    }

    @Transactional
    public NotificationDtos.NotificationResponse update(String id, boolean read, UserEntity actor) {
        NotificationEntity notification = notifications.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found."));
        patientAccess.getVisible(notification.getPatientId(), actor);
        notification.setRead(read);
        audit.record(actor.getId(), "NOTIFICATION_READ", "NOTIFICATION", id, Map.of("read", read));
        return NotificationDtos.NotificationResponse.from(notifications.save(notification));
    }
}
