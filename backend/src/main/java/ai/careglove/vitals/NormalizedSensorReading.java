package ai.careglove.vitals;

import java.time.Instant;

public record NormalizedSensorReading(
        String patientId,
        String deviceId,
        Instant timestamp,
        Double heartRate,
        Double gsr,
        Double temperature,
        String source,
        Instant receivedAt,
        Double signalQuality,
        String firmwareVersion) {}
