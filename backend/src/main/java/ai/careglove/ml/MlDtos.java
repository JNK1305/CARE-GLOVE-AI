package ai.careglove.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public final class MlDtos {
    private MlDtos() {}

    public record PredictionRequest(
            @JsonProperty("user_id") String userId,
            Instant timestamp,
            @JsonProperty("heart_rate") Double heartRate,
            Double gsr,
            Double temperature,
            @JsonProperty("patient_id") String patientId,
            @JsonProperty("device_id") String deviceId,
            String source,
            @JsonProperty("received_at") Instant receivedAt,
            @JsonProperty("signal_quality") Double signalQuality,
            @JsonProperty("firmware_version") String firmwareVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PredictionResponse(
            @JsonProperty("patient_id") String patientId,
            Instant timestamp,
            String state,
            @JsonProperty("anomaly_score") Double anomalyScore,
            @JsonProperty("model_version") String modelVersion,
            List<Contributor> contributors,
            @JsonProperty("data_quality") DataQuality dataQuality,
            @JsonProperty("scored_window") boolean windowScored,
            @JsonProperty("severity") String severity,
            @JsonProperty("reason") String reason,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("classifier_version") String classifierVersion) {

        public PredictionResponse(
                String patientId,
                Instant timestamp,
                String state,
                Double anomalyScore,
                String modelVersion,
                List<Contributor> contributors,
                DataQuality dataQuality,
                boolean windowScored) {
            this(patientId, timestamp, state, anomalyScore, modelVersion, contributors, dataQuality, windowScored,
                 "POTENTIAL_ANOMALY".equals(state) ? "WARNING" : "NORMAL", null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contributor(
            String signal,
            String direction,
            @JsonProperty("current_value") Double currentValue,
            @JsonProperty("baseline_value") Double baselineValue,
            Double deviation,
            Double contribution) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataQuality(
            boolean valid,
            @JsonProperty("missing_signals") List<String> missingSignals,
            List<String> reasons,
            @JsonProperty("window_readings") int windowReadings) {}
}
