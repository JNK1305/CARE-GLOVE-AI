package ai.careglove.vitals;

import java.time.Instant;

public record SensorReadingResponse(
        String id,
        String patientId,
        String deviceId,
        Instant timestamp,
        Double heartRate,
        Double gsr,
        Double temperature,
        String state,
        String source,
        Instant receivedAt,
        Double signalQuality,
        String firmwareVersion) {
    static SensorReadingResponse from(SensorReadingEntity reading) {
        return new SensorReadingResponse(
                reading.getId(), reading.getPatientId(), reading.getDeviceId(), reading.getTimestamp(),
                reading.getHeartRate(), reading.getGsr(), reading.getTemperature(),
                reading.getState(), reading.getSource(), reading.getReceivedAt(),
                reading.getSignalQuality(), reading.getFirmwareVersion());
    }
}
