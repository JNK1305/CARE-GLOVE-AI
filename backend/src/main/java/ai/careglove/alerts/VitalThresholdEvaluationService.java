package ai.careglove.alerts;

import ai.careglove.audit.AuditService;
import ai.careglove.journey.PatientJourneyEntity;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.ml.MlPredictionEntity;
import ai.careglove.ml.MlPredictionRepository;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.vitals.SensorReadingEntity;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates real-time patient vital sign readings against configured thresholds.
 *
 * CLINICAL DISCLAIMER:
 * These thresholds represent configured prototype reference ranges and are not clinically
 * validated diagnostic criteria. System does not provide medical diagnosis or treatment.
 */
@Service
public class VitalThresholdEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(VitalThresholdEvaluationService.class);

    public static final String CLINICAL_DISCLAIMER =
            "Thresholds represent prototype reference ranges and are not clinically validated diagnostic criteria.";

    // Heart rate thresholds (BPM)
    public static final double HR_CRITICAL_HIGH = 140.0;
    public static final double HR_WARNING_HIGH = 120.0;
    public static final double HR_WARNING_LOW = 50.0;
    public static final double HR_CRITICAL_LOW = 45.0;

    // Skin temperature thresholds (°C)
    public static final double TEMP_CRITICAL_HIGH = 39.5;
    public static final double TEMP_WARNING_HIGH = 38.0;
    public static final double TEMP_WARNING_LOW = 35.5;
    public static final double TEMP_CRITICAL_LOW = 34.0;

    // Skin conductance / GSR thresholds (µS)
    public static final double GSR_CRITICAL_HIGH = 12.0;
    public static final double GSR_WARNING_HIGH = 10.0;
    public static final double GSR_NORMAL_MIN = 0.1;

    private static final List<String> ACTIVE_STATUSES = List.of("OPEN", "ACKNOWLEDGED");

    private final AlertRepository alerts;
    private final NotificationRepository notifications;
    private final PatientJourneyRepository journey;
    private final PatientRepository patients;
    private final MlPredictionRepository predictions;
    private final AuditService audit;

    public VitalThresholdEvaluationService(
            AlertRepository alerts,
            NotificationRepository notifications,
            PatientJourneyRepository journey,
            PatientRepository patients,
            MlPredictionRepository predictions,
            AuditService audit) {
        this.alerts = alerts;
        this.notifications = notifications;
        this.journey = journey;
        this.patients = patients;
        this.predictions = predictions;
        this.audit = audit;
    }

    @Transactional
    public List<AlertDtos.AlertResponse> evaluateReading(
            SensorReadingEntity reading,
            PatientEntity patient,
            UserEntity actor) {

        if (reading == null || patient == null) {
            return List.of();
        }

        // Do not generate alerts from demo generated sensor data (Requirement 6 & 8)
        if ("DEMO_SENSOR".equalsIgnoreCase(reading.getSource())) {
            log.debug("Skipping alert generation for DEMO_SENSOR reading {}", reading.getId());
            return List.of();
        }

        Instant timestamp = reading.getTimestamp() != null ? reading.getTimestamp() : Instant.now();
        List<AlertEntity> resultAlerts = new ArrayList<>();

        // Ensure foreign-key parent in ml_predictions table exists
        MlPredictionEntity predictionRecord = ensurePredictionRecord(reading, timestamp);

        // 1. Evaluate Heart Rate
        evaluateParameter(
                reading, patient, actor, predictionRecord,
                "BPM", reading.getHeartRate(),
                checkHeartRate(reading.getHeartRate()),
                resultAlerts);

        // 2. Evaluate Temperature
        evaluateParameter(
                reading, patient, actor, predictionRecord,
                "Temperature", reading.getTemperature(),
                checkTemperature(reading.getTemperature()),
                resultAlerts);

        // 3. Evaluate GSR
        evaluateParameter(
                reading, patient, actor, predictionRecord,
                "GSR", reading.getGsr(),
                checkGsr(reading.getGsr()),
                resultAlerts);

        // Update patient vital numbers and overall status
        updatePatientState(patient, reading);

        return resultAlerts.stream().map(AlertDtos.AlertResponse::from).toList();
    }

    private void evaluateParameter(
            SensorReadingEntity reading,
            PatientEntity patient,
            UserEntity actor,
            MlPredictionEntity predictionRecord,
            String parameter,
            Double value,
            ThresholdResult thresholdResult,
            List<AlertEntity> resultAlerts) {

        if (thresholdResult.status() == VitalStatus.MISSING) {
            log.debug("Vital parameter {} is missing for patient {}; not classified as normal.", parameter, patient.getId());
            return;
        }

        // Check if there are active alerts for this patient and parameter
        List<AlertEntity> existingActive = alerts.findByPatientIdAndParameterAndStatusIn(
                patient.getId(), parameter, ACTIVE_STATUSES);

        if (thresholdResult.status() == VitalStatus.NORMAL) {
            // Patient reading returned to normal: resolve existing open/acknowledged alerts for this parameter
            for (AlertEntity active : existingActive) {
                active.setStatus("RESOLVED");
                active.setResolvedAt(reading.getTimestamp());
                alerts.save(active);

                String msg = parameter + " readings returned to normal range (" + value + ").";
                recordJourney(patient, "RECOVERY", actor != null ? actor.getName() : "CARE-GLOVE monitor",
                        msg, reading.getTimestamp(), "INFO");
                if (actor != null) {
                    audit.record(actor.getId(), "ALERT_RESOLVED", "ALERT", active.getId(),
                            Map.of("parameter", parameter, "value", value));
                }
                log.info("Resolved active alert {} for patient {} parameter {} (value={})",
                        active.getId(), patient.getId(), parameter, value);
            }
            return;
        }

        // Abnormal state: CRITICAL or WARNING
        String severity = thresholdResult.status().name();
        String dedupKey = severity + ":" + patient.getId() + ":" + reading.getDeviceId() + ":" + parameter;

        // Check for existing active alert for this parameter
        AlertEntity currentActive = existingActive.isEmpty() ? null : existingActive.get(0);

        if (currentActive != null) {
            if (currentActive.getSeverity().equalsIgnoreCase(severity)) {
                // Deduplication: update measurement values without creating redundant alerts
                currentActive.setValue(value != null ? value : currentActive.getValue());
                currentActive.setHeartRate(reading.getHeartRate());
                currentActive.setGsr(reading.getGsr());
                currentActive.setTemperature(reading.getTemperature());
                currentActive.setTimestamp(reading.getTimestamp());
                currentActive.setThreshold(thresholdResult.threshold());
                currentActive = alerts.save(currentActive);

                if (actor != null) {
                    audit.record(actor.getId(), "ALERT_DEDUPLICATED", "ALERT", currentActive.getId(),
                            Map.of("parameter", parameter, "severity", severity, "value", value));
                }
                resultAlerts.add(currentActive);
                log.info("Deduplicated {} alert {} for patient {} parameter {} (value={})",
                        severity, currentActive.getId(), patient.getId(), parameter, value);
                return;
            } else if ("WARNING".equalsIgnoreCase(currentActive.getSeverity()) && "CRITICAL".equalsIgnoreCase(severity)) {
                // Escalation: resolve prior warning alert and create new critical alert
                currentActive.setStatus("RESOLVED");
                currentActive.setResolvedAt(reading.getTimestamp());
                alerts.save(currentActive);
                log.info("Escalating alert for patient {} parameter {} from WARNING to CRITICAL", patient.getId(), parameter);
            } else {
                // Prior critical alert remains open; update its current readings
                currentActive.setValue(value != null ? value : currentActive.getValue());
                currentActive.setHeartRate(reading.getHeartRate());
                currentActive.setGsr(reading.getGsr());
                currentActive.setTemperature(reading.getTemperature());
                currentActive.setTimestamp(reading.getTimestamp());
                alerts.save(currentActive);
                resultAlerts.add(currentActive);
                return;
            }
        }

        // Create new Alert
        AlertEntity newAlert = new AlertEntity();
        newAlert.setId(UUID.randomUUID().toString());
        newAlert.setPatientId(patient.getId());
        newAlert.setDeviceId(reading.getDeviceId());
        newAlert.setTimestamp(reading.getTimestamp());
        newAlert.setSeverity(severity);
        newAlert.setMonitoringState("POTENTIAL_ANOMALY");
        newAlert.setTrigger("VITAL_THRESHOLD");
        newAlert.setSource(reading.getSource());
        newAlert.setPredictionId(predictionRecord.getId());
        newAlert.setParameter(parameter);
        newAlert.setValue(value != null ? value : 0.0);
        newAlert.setThreshold(thresholdResult.threshold());
        newAlert.setExplanation(thresholdResult.explanation());
        newAlert.setStatus("OPEN");
        newAlert.setHeartRate(reading.getHeartRate());
        newAlert.setGsr(reading.getGsr());
        newAlert.setTemperature(reading.getTemperature());
        newAlert.setModelVersion("vital-threshold-v1");
        newAlert.setModelScore(1.0);
        newAlert.setDeduplicationKey(dedupKey);
        newAlert.setCreatedAt(Instant.now());
        newAlert = alerts.save(newAlert);

        // Create Notification
        NotificationEntity notification = new NotificationEntity();
        notification.setId(UUID.randomUUID().toString());
        notification.setAlertId(newAlert.getId());
        notification.setPatientId(patient.getId());
        notification.setSeverity(newAlert.getSeverity());
        notification.setParameter(newAlert.getParameter());
        notification.setValue(newAlert.getValue());
        notification.setTimestamp(newAlert.getTimestamp());
        notification.setRead(false);
        notifications.save(notification);

        // Create Patient Journey event
        recordJourney(patient, "ALERT",
                actor != null ? actor.getName() : "CARE-GLOVE monitor",
                newAlert.getExplanation(), newAlert.getTimestamp(), newAlert.getSeverity());

        if (actor != null) {
            audit.record(actor.getId(), "ALERT_CREATED", "ALERT", newAlert.getId(),
                    Map.of("parameter", parameter, "severity", severity, "value", value));
        }

        log.info("Created new {} alert for patient {} on parameter {} (value={}, threshold={})",
                severity, patient.getId(), parameter, value, thresholdResult.threshold());

        resultAlerts.add(newAlert);
    }

    private void updatePatientState(PatientEntity patient, SensorReadingEntity reading) {
        if (reading.getHeartRate() != null) patient.setBpm(reading.getHeartRate());
        if (reading.getTemperature() != null) patient.setTemp(reading.getTemperature());
        if (reading.getGsr() != null) patient.setGsr(reading.getGsr());

        List<AlertEntity> openAlerts = alerts.findByPatientIdAndStatusInOrderByCreatedAtDesc(
                patient.getId(), ACTIVE_STATUSES);

        boolean hasCritical = openAlerts.stream().anyMatch(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity()));
        boolean hasWarning = openAlerts.stream().anyMatch(a -> "WARNING".equalsIgnoreCase(a.getSeverity()));

        if (hasCritical) {
            patient.setStatus("Critical");
        } else if (hasWarning) {
            patient.setStatus("Watch");
        } else {
            patient.setStatus("Stable");
        }

        patients.save(patient);
    }

    private MlPredictionEntity ensurePredictionRecord(SensorReadingEntity reading, Instant timestamp) {
        return predictions.findByReadingId(reading.getId()).orElseGet(() -> {
            MlPredictionEntity pred = new MlPredictionEntity();
            pred.setId(UUID.randomUUID().toString());
            pred.setReadingId(reading.getId());
            pred.setPatientId(reading.getPatientId());
            pred.setTimestamp(timestamp);
            pred.setState("POTENTIAL_ANOMALY");
            pred.setModelVersion("vital-threshold-v1");
            pred.setContributors("[]");
            pred.setDataQualityValid(true);
            pred.setDataQuality("{\"valid\":true,\"missing_signals\":[],\"reasons\":[],\"window_readings\":1}");
            pred.setScoredWindow(true);
            pred.setCreatedAt(Instant.now());
            return predictions.saveAndFlush(pred);
        });
    }

    private void recordJourney(
            PatientEntity patient, String type, String actor, String description, Instant timestamp, String severity) {
        PatientJourneyEntity event = new PatientJourneyEntity();
        event.setId(UUID.randomUUID().toString());
        event.setPatientId(patient.getId());
        event.setType(type);
        event.setActor(actor);
        event.setDescription(description);
        event.setTimestamp(timestamp);
        event.setSeverity(severity);
        journey.save(event);
    }

    public ThresholdResult checkHeartRate(Double hr) {
        if (hr == null) {
            return new ThresholdResult(VitalStatus.MISSING, null, "Heart rate data missing.");
        }
        if (hr > HR_CRITICAL_HIGH) {
            return new ThresholdResult(
                    VitalStatus.CRITICAL,
                    HR_CRITICAL_HIGH,
                    "Critical tachycardia: Heart rate of " + Math.round(hr) + " BPM exceeds critical threshold (" + HR_CRITICAL_HIGH + " BPM). " + CLINICAL_DISCLAIMER);
        }
        if (hr < HR_CRITICAL_LOW) {
            return new ThresholdResult(
                    VitalStatus.CRITICAL,
                    HR_CRITICAL_LOW,
                    "Critical bradycardia: Heart rate of " + Math.round(hr) + " BPM is below critical threshold (" + HR_CRITICAL_LOW + " BPM). " + CLINICAL_DISCLAIMER);
        }
        if (hr > HR_WARNING_HIGH) {
            return new ThresholdResult(
                    VitalStatus.WARNING,
                    HR_WARNING_HIGH,
                    "Elevated heart rate: Heart rate of " + Math.round(hr) + " BPM exceeds warning threshold (" + HR_WARNING_HIGH + " BPM). " + CLINICAL_DISCLAIMER);
        }
        if (hr < HR_WARNING_LOW) {
            return new ThresholdResult(
                    VitalStatus.WARNING,
                    HR_WARNING_LOW,
                    "Low heart rate: Heart rate of " + Math.round(hr) + " BPM is below warning threshold (" + HR_WARNING_LOW + " BPM). " + CLINICAL_DISCLAIMER);
        }
        return new ThresholdResult(VitalStatus.NORMAL, null, "Heart rate within normal range (50-120 BPM).");
    }

    public ThresholdResult checkTemperature(Double temp) {
        if (temp == null) {
            return new ThresholdResult(VitalStatus.MISSING, null, "Temperature data missing.");
        }
        if (temp > TEMP_CRITICAL_HIGH) {
            return new ThresholdResult(
                    VitalStatus.CRITICAL,
                    TEMP_CRITICAL_HIGH,
                    "Critical hyperpyrexia: Temperature of " + temp + " °C exceeds critical threshold (" + TEMP_CRITICAL_HIGH + " °C). " + CLINICAL_DISCLAIMER);
        }
        if (temp < TEMP_CRITICAL_LOW) {
            return new ThresholdResult(
                    VitalStatus.CRITICAL,
                    TEMP_CRITICAL_LOW,
                    "Critical hypothermia: Temperature of " + temp + " °C is below critical threshold (" + TEMP_CRITICAL_LOW + " °C). " + CLINICAL_DISCLAIMER);
        }
        if (temp > TEMP_WARNING_HIGH) {
            return new ThresholdResult(
                    VitalStatus.WARNING,
                    TEMP_WARNING_HIGH,
                    "Elevated temperature (fever): Temperature of " + temp + " °C exceeds warning threshold (" + TEMP_WARNING_HIGH + " °C). " + CLINICAL_DISCLAIMER);
        }
        if (temp < TEMP_WARNING_LOW) {
            return new ThresholdResult(
                    VitalStatus.WARNING,
                    TEMP_WARNING_LOW,
                    "Low body temperature: Temperature of " + temp + " °C is below warning threshold (" + TEMP_WARNING_LOW + " °C). " + CLINICAL_DISCLAIMER);
        }
        return new ThresholdResult(VitalStatus.NORMAL, null, "Temperature within normal range (35.5-38.0 °C).");
    }

    public ThresholdResult checkGsr(Double gsr) {
        if (gsr == null) {
            return new ThresholdResult(VitalStatus.MISSING, null, "Skin conductance (GSR) data missing.");
        }
        if (gsr > GSR_CRITICAL_HIGH) {
            return new ThresholdResult(
                    VitalStatus.CRITICAL,
                    GSR_CRITICAL_HIGH,
                    "Critical skin conductance: GSR of " + gsr + " µS exceeds critical threshold (" + GSR_CRITICAL_HIGH + " µS). " + CLINICAL_DISCLAIMER);
        }
        if (gsr > GSR_WARNING_HIGH) {
            return new ThresholdResult(
                    VitalStatus.WARNING,
                    GSR_WARNING_HIGH,
                    "Elevated skin conductance: GSR of " + gsr + " µS exceeds warning threshold (" + GSR_WARNING_HIGH + " µS). " + CLINICAL_DISCLAIMER);
        }
        return new ThresholdResult(VitalStatus.NORMAL, null, "GSR within normal range (0.1-10.0 µS).");
    }

    public enum VitalStatus {
        NORMAL,
        WARNING,
        CRITICAL,
        MISSING
    }

    public record ThresholdResult(VitalStatus status, Double threshold, String explanation) {}
}

