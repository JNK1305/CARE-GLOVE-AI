package ai.careglove.ml;

import ai.careglove.vitals.NormalizedSensorReading;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MlServiceClient {
    private final RestClient client;

    public MlServiceClient(RestClient mlRestClient) {
        this.client = mlRestClient;
    }

    public MlDtos.PredictionResponse predict(NormalizedSensorReading reading) {
        MlDtos.PredictionRequest request = new MlDtos.PredictionRequest(
                reading.patientId(),
                reading.timestamp(),
                reading.heartRate(),
                reading.gsr(),
                reading.temperature(),
                reading.patientId(),
                reading.deviceId(),
                reading.source(),
                reading.receivedAt(),
                reading.signalQuality(),
                reading.firmwareVersion());
        MlDtos.PredictionResponse response = client.post()
                .uri("/api/v1/predict")
                .body(request)
                .retrieve()
                .body(MlDtos.PredictionResponse.class);
        if (response == null || response.patientId() == null || !response.patientId().equals(reading.patientId())) {
            throw new IllegalStateException("ML service returned a response for an unexpected patient.");
        }
        return response;
    }
}
