package ai.careglove.alerts;

import java.time.Instant;

public final class AlertDtos {
    private AlertDtos() {}

    public record AlertResponse(
            String id,
            String patientId,
            String gloveId,
            String parameter,
            double value,
            Double threshold,
            String severity,
            Instant timestamp,
            String source,
            String status,
            String monitoringState,
            String trigger,
            String explanation,
            Instant acknowledgedAt,
            String acknowledgedBy,
            Instant resolvedAt,
            Instant createdAt,
            Double heartRate,
            Double gsr,
            Double temperature,
            String modelVersion,
            Double modelScore,
            String deduplicationKey) {
        public static AlertResponse from(AlertEntity entity) {
            return new AlertResponse(
                    entity.getId(), entity.getPatientId(), entity.getDeviceId(), entity.getParameter(),
                    entity.getValue(), entity.getThreshold(), entity.getSeverity(), entity.getTimestamp(),
                    entity.getSource(), entity.getStatus(), entity.getMonitoringState(), entity.getTrigger(),
                    entity.getExplanation(), entity.getAcknowledgedAt(), entity.getAcknowledgedBy(), entity.getResolvedAt(),
                    entity.getCreatedAt(), entity.getHeartRate(), entity.getGsr(), entity.getTemperature(),
                    entity.getModelVersion(), entity.getModelScore(), entity.getDeduplicationKey());
        }
    }

    public record UpdateStatusRequest(String status) {}
}
