package ai.careglove.alerts;

import java.time.Instant;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record NotificationResponse(
            String id,
            String alertId,
            String patientId,
            String severity,
            String parameter,
            double value,
            Instant timestamp,
            boolean read) {
        static NotificationResponse from(NotificationEntity entity) {
            return new NotificationResponse(entity.getId(), entity.getAlertId(), entity.getPatientId(),
                    entity.getSeverity(), entity.getParameter(), entity.getValue(), entity.getTimestamp(), entity.isRead());
        }
    }

    public record ReadRequest(@jakarta.validation.constraints.NotNull Boolean read) {}
}
