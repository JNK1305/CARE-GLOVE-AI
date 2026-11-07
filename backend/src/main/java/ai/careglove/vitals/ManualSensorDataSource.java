package ai.careglove.vitals;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ManualSensorDataSource implements SensorDataSource {
    @Override
    public NormalizedSensorReading normalize(IngestReadingRequest request, Instant receivedAt) {
        return new NormalizedSensorReading(
                request.patientId(),
                request.deviceId(),
                request.timestamp(),
                request.heartRate(),
                request.gsr(),
                request.temperature(),
                request.source() == null ? "MANUAL" : request.source(),
                receivedAt,
                request.signalQuality(),
                request.firmwareVersion());
    }
}
