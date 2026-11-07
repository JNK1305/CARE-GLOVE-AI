package ai.careglove.vitals;

import java.time.Instant;

public interface SensorDataSource {
    NormalizedSensorReading normalize(IngestReadingRequest request, Instant receivedAt);
}
