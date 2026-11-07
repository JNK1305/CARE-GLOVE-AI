package ai.careglove.firebase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FirebaseSensorReading {
    @JsonProperty("bpm")
    private Double bpm;

    @JsonProperty("heart_rate")
    private Double heartRate;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("max30105_temp")
    private Double max30105Temp;

    @JsonProperty("gsr")
    private Double gsr;

    @JsonProperty("gsr_conductance")
    private Double gsrConductance;

    @JsonProperty("gsr_raw")
    private Double gsrRaw;

    @JsonProperty("spo2")
    private Double spo2;

    @JsonProperty("ir")
    private Double ir;

    @JsonProperty("led_level")
    private Integer ledLevel;

    @JsonProperty("stress")
    private String stress;

    @JsonProperty("stress_level")
    private String stressLevel;

    @JsonProperty("timestamp")
    private Long timestamp;

    @JsonProperty("deviceId")
    private String deviceId;

    private String recordKey;
    private String sourcePath;

    public FirebaseSensorReading() {}

    public Double resolveBpm() {
        if (bpm != null) return bpm;
        return heartRate;
    }

    public Double resolveTemperature() {
        if (temperature != null) return temperature;
        return max30105Temp;
    }

    public Double resolveGsr() {
        if (gsr != null) return gsr;
        if (gsrConductance != null) return gsrConductance;
        // If only raw ADC is available and conductance is 0, provide default scaled conductance
        return 0.0;
    }

    public boolean isSensorContactAdequate() {
        // gsr_raw == 4095 indicates 12-bit ADC rail maximum (infinite impedance / open circuit / detached leads)
        if (gsrRaw != null && gsrRaw >= 4090) {
            return false;
        }
        // ir < 15000 indicates optical photoplethysmogram sensor is not receiving skin reflection (ambient noise)
        if (ir != null && ir < 15000) {
            return false;
        }
        return true;
    }

    public Double calculateSignalQuality() {
        if (!isSensorContactAdequate()) {
            return 0.05;
        }
        if (ir != null && ir >= 40000 && gsrRaw != null && gsrRaw < 3800) {
            return 0.95;
        }
        return 0.75;
    }

    public Instant resolveTimestamp(Instant fallback) {
        if (timestamp == null) {
            return fallback != null ? fallback.truncatedTo(java.time.temporal.ChronoUnit.MILLIS) : Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        }
        // If timestamp is epoch millis (e.g. > 1_000_000_000_000L ~ Sep 2001)
        if (timestamp > 1_000_000_000_000L) {
            return Instant.ofEpochMilli(timestamp);
        }
        // Otherwise relative uptime or device millis: use current ingestion time
        return fallback != null ? fallback.truncatedTo(java.time.temporal.ChronoUnit.MILLIS) : Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    public ValidationResult validate() {
        Double bpmVal = resolveBpm();
        if (bpmVal == null) {
            return ValidationResult.invalid("Heart rate (BPM) is missing.");
        }
        if (bpmVal < 20.0 || bpmVal > 260.0) {
            return ValidationResult.invalid("BPM " + bpmVal + " outside physiological bounds [20, 260].");
        }

        Double tempVal = resolveTemperature();
        if (tempVal == null) {
            return ValidationResult.invalid("Temperature is missing.");
        }
        if (tempVal < 25.0 || tempVal > 45.0) {
            return ValidationResult.invalid("Temperature " + tempVal + " °C outside physiological bounds [25, 45].");
        }

        Double gsrVal = resolveGsr();
        if (gsrVal == null) {
            return ValidationResult.invalid("GSR is missing.");
        }
        if (gsrVal < 0.0 || gsrVal > 100.0) {
            return ValidationResult.invalid("GSR " + gsrVal + " µS outside physiological bounds [0, 100].");
        }

        if (timestamp != null && timestamp > 1_000_000_000_000L) {
            Instant t = Instant.ofEpochMilli(timestamp);
            if (t.isAfter(Instant.now().plusSeconds(300))) {
                return ValidationResult.invalid("Timestamp " + t + " is too far in the future.");
            }
        }

        return ValidationResult.valid();
    }

    // Getters and Setters
    public Double getBpm() { return bpm; }
    public void setBpm(Double bpm) { this.bpm = bpm; }

    public Double getHeartRate() { return heartRate; }
    public void setHeartRate(Double heartRate) { this.heartRate = heartRate; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Double getMax30105Temp() { return max30105Temp; }
    public void setMax30105Temp(Double max30105Temp) { this.max30105Temp = max30105Temp; }

    public Double getGsr() { return gsr; }
    public void setGsr(Double gsr) { this.gsr = gsr; }

    public Double getGsrConductance() { return gsrConductance; }
    public void setGsrConductance(Double gsrConductance) { this.gsrConductance = gsrConductance; }

    public Double getGsrRaw() { return gsrRaw; }
    public void setGsrRaw(Double gsrRaw) { this.gsrRaw = gsrRaw; }

    public Double getSpo2() { return spo2; }
    public void setSpo2(Double spo2) { this.spo2 = spo2; }

    public Double getIr() { return ir; }
    public void setIr(Double ir) { this.ir = ir; }

    public Integer getLedLevel() { return ledLevel; }
    public void setLedLevel(Integer ledLevel) { this.ledLevel = ledLevel; }

    public String getStress() { return stress; }
    public void setStress(String stress) { this.stress = stress; }

    public String getStressLevel() { return stressLevel; }
    public void setStressLevel(String stressLevel) { this.stressLevel = stressLevel; }

    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getRecordKey() { return recordKey; }
    public void setRecordKey(String recordKey) { this.recordKey = recordKey; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public record ValidationResult(boolean isValid, String reason) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
