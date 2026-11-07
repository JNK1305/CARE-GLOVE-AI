package ai.careglove.ml;

import ai.careglove.alerts.AlertDtos;
import ai.careglove.alerts.AlertEntity;
import ai.careglove.alerts.AlertRepository;
import ai.careglove.alerts.NotificationEntity;
import ai.careglove.alerts.NotificationRepository;
import ai.careglove.audit.AuditService;
import ai.careglove.journey.PatientJourneyEntity;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.vitals.MonitoringSessionEntity;
import ai.careglove.vitals.MonitoringSessionRepository;
import ai.careglove.vitals.SensorReadingEntity;
import ai.careglove.vitals.SensorReadingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlPredictionProcessingService {
    private final MlPredictionRepository predictions;
    private final SensorReadingRepository readings;
    private final PatientRepository patients;
    private final MonitoringSessionRepository sessions;
    private final AlertRepository alerts;
    private final NotificationRepository notifications;
    private final PatientJourneyRepository journey;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final int persistenceWindows;
    private final int recoveryWindows;
    private final int cooldownSeconds;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final java.util.concurrent.ConcurrentMap<String, Object> patientLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public MlPredictionProcessingService(
            MlPredictionRepository predictions,
            SensorReadingRepository readings,
            PatientRepository patients,
            MonitoringSessionRepository sessions,
            AlertRepository alerts,
            NotificationRepository notifications,
            PatientJourneyRepository journey,
            AuditService audit,
            ObjectMapper mapper,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @Value("${careglove.ml.persistence-windows:3}") int persistenceWindows,
            @Value("${careglove.ml.recovery-windows:3}") int recoveryWindows,
            @Value("${careglove.ml.cooldown-seconds:900}") int cooldownSeconds) {
        this.predictions = predictions;
        this.readings = readings;
        this.patients = patients;
        this.sessions = sessions;
        this.alerts = alerts;
        this.notifications = notifications;
        this.journey = journey;
        this.audit = audit;
        this.mapper = mapper;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        if (persistenceWindows < 1 || recoveryWindows < 1 || cooldownSeconds < 0) {
            throw new IllegalStateException("Invalid backend alert persistence configuration.");
        }
        this.persistenceWindows = persistenceWindows;
        this.recoveryWindows = recoveryWindows;
        this.cooldownSeconds = cooldownSeconds;
    }

    public AlertDtos.AlertResponse storePrediction(
            SensorReadingEntity reading, MlDtos.PredictionResponse result, UserEntity actor) {
        synchronized (patientLocks.computeIfAbsent(reading.getPatientId(), k -> new Object())) {
            return transactionTemplate.execute(status -> doStorePrediction(reading, result, actor));
        }
    }

    private AlertDtos.AlertResponse doStorePrediction(
            SensorReadingEntity reading, MlDtos.PredictionResponse result, UserEntity actor) {
        boolean timestampMatch = reading.getTimestamp().equals(result.timestamp())
                || Math.abs(java.time.Duration.between(reading.getTimestamp(), result.timestamp()).toMillis()) <= 50;
        if (!reading.getPatientId().equals(result.patientId())
                || !timestampMatch
                || result.dataQuality() == null
                || !List.of("NORMAL", "MONITOR", "POTENTIAL_ANOMALY").contains(result.state())) {
            throw new IllegalStateException("ML service returned a mismatched or unsupported prediction.");
        }
        MlPredictionEntity prediction = predictions.findByReadingId(reading.getId()).orElseGet(MlPredictionEntity::new);
        prediction.setId(prediction.getId() == null ? UUID.randomUUID().toString() : prediction.getId());
        prediction.setReadingId(reading.getId());
        prediction.setPatientId(reading.getPatientId());
        prediction.setTimestamp(reading.getTimestamp());
        prediction.setState(result.state());
        prediction.setAnomalyScore(result.anomalyScore());
        prediction.setModelVersion(result.modelVersion());
        prediction.setContributors(json(result.contributors()));
        prediction.setDataQualityValid(result.dataQuality().valid());
        prediction.setDataQuality(json(result.dataQuality()));
        prediction.setScoredWindow(result.windowScored());
        prediction = predictions.saveAndFlush(prediction);
        reading.setState(result.state());
        readings.save(reading);

        PatientEntity patient = patients.findById(reading.getPatientId()).orElseThrow();
        MonitoringSessionEntity session = sessions.findFirstByPatientIdAndStatusOrderByStartedAtDesc(patient.getId(), "ACTIVE")
                .orElseGet(() -> startSession(patient, reading));
        AlertEntity alert = null;
        String computedSeverity = resolveSeverity(reading, result);
        if ("CRITICAL".equals(computedSeverity) || "WARNING".equals(computedSeverity) || result.windowScored()) {
            if ("CRITICAL".equals(computedSeverity) || "WARNING".equals(computedSeverity) || "POTENTIAL_ANOMALY".equals(result.state())) {
                session.setNormalWindowCount(0);
                if (!session.isActive()) {
                    session.setActive(true);
                    session.setPersistenceStartedAt(result.timestamp());
                    session.setAnomalousWindowCount(0);
                    session.setRecoveredAt(null);
                }
                session.setAnomalousWindowCount(session.getAnomalousWindowCount() + 1);
                session.setDurationSeconds(Math.max(0, result.timestamp().getEpochSecond() - session.getPersistenceStartedAt().getEpochSecond()));
                String dedupKey = computedSeverity + ":" + patient.getId() + ":" + reading.getDeviceId();
                var existingActive = alerts.findFirstByDeduplicationKeyAndStatusInOrderByCreatedAtDesc(
                        dedupKey, List.of("OPEN", "ACKNOWLEDGED"));
                if (existingActive.isPresent()) {
                    AlertEntity activeAlert = existingActive.get();
                    activeAlert.setValue(reading.getHeartRate() != null ? reading.getHeartRate() : activeAlert.getValue());
                    activeAlert.setHeartRate(reading.getHeartRate());
                    activeAlert.setGsr(reading.getGsr());
                    activeAlert.setTemperature(reading.getTemperature());
                    activeAlert.setTimestamp(result.timestamp());
                    alert = alerts.save(activeAlert);
                    audit.record(actor.getId(), "ALERT_DEDUPLICATED", "ALERT", activeAlert.getId(),
                            Map.of("state", result.state(), "severity", computedSeverity));
                } else if ("CRITICAL".equals(computedSeverity) || "WARNING".equals(computedSeverity)) {
                    alert = createAlert(reading, prediction, result, patient, actor);
                    session.setLastAlertAt(result.timestamp());
                } else if (session.getAnomalousWindowCount() >= persistenceWindows) {
                    boolean coolingDown = session.getCooldownUntil() != null && result.timestamp().isBefore(session.getCooldownUntil());
                    if (!coolingDown) {
                        alert = createAlert(reading, prediction, result, patient, actor);
                        session.setLastAlertAt(result.timestamp());
                        session.setCooldownUntil(result.timestamp().plusSeconds(cooldownSeconds));
                    }
                }
            } else if ("NORMAL".equals(result.state()) && "NORMAL".equals(computedSeverity)) {
                session.setAnomalousWindowCount(0);
                session.setNormalWindowCount(session.getNormalWindowCount() + 1);
                if (session.isActive() && session.getNormalWindowCount() >= recoveryWindows) {
                    session.setActive(false);
                    session.setRecoveredAt(result.timestamp());
                    session.setPersistenceStartedAt(null);
                    session.setDurationSeconds(0);
                    session.setAnomalousWindowCount(0);
                    session.setCooldownUntil(null);
                    alerts.findFirstByPatientIdAndSeverityAndStatusInOrderByCreatedAtDesc(
                            patient.getId(), "CRITICAL", List.of("OPEN", "ACKNOWLEDGED"))
                            .ifPresent(a -> {
                                a.setStatus("RESOLVED");
                                a.setResolvedAt(result.timestamp());
                                alerts.save(a);
                            });
                    alerts.findFirstByPatientIdAndSeverityAndStatusInOrderByCreatedAtDesc(
                            patient.getId(), "WARNING", List.of("OPEN", "ACKNOWLEDGED"))
                            .ifPresent(a -> {
                                a.setStatus("RESOLVED");
                                a.setResolvedAt(result.timestamp());
                                alerts.save(a);
                            });
                    journey(patient, "RECOVERY", "ML monitoring pipeline", "Anomalous state recovered", result.timestamp(), "INFO");
                }
            } else {
                session.setAnomalousWindowCount(0);
                session.setNormalWindowCount(0);
            }
        }
        sessions.save(session);
        patient.setBpm(reading.getHeartRate());
        patient.setTemp(reading.getTemperature());
        patient.setGsr(reading.getGsr());
        if ("CRITICAL".equals(computedSeverity)) {
            patient.setStatus("Critical");
        } else if ("WARNING".equals(computedSeverity)) {
            if (!"Critical".equals(patient.getStatus())) {
                patient.setStatus("Watch");
            }
        } else if ("NORMAL".equals(computedSeverity)) {
            if (!"Critical".equals(patient.getStatus())) {
                patient.setStatus("Stable");
            }
        }
        patients.save(patient);
        audit.record(actor.getId(), "ML_PREDICTION", "ML_PREDICTION", prediction.getId(), Map.of("state", prediction.getState()));
        if (alert == null) return null;
        return AlertDtos.AlertResponse.from(alert);
    }

    public String resolveSeverity(SensorReadingEntity reading, MlDtos.PredictionResponse result) {
        if (result != null && "CRITICAL".equalsIgnoreCase(result.severity())) {
            return "CRITICAL";
        }
        if (reading.getHeartRate() != null && (reading.getHeartRate() > 140.0 || reading.getHeartRate() < 45.0)) {
            return "CRITICAL";
        }
        if (reading.getTemperature() != null && (reading.getTemperature() > 39.5 || reading.getTemperature() < 34.0)) {
            return "CRITICAL";
        }
        if (reading.getGsr() != null && reading.getGsr() > 12.0) {
            return "CRITICAL";
        }
        if (result != null && ("WARNING".equalsIgnoreCase(result.severity()) || "POTENTIAL_ANOMALY".equalsIgnoreCase(result.state()))) {
            return "WARNING";
        }
        if (reading.getHeartRate() != null && (reading.getHeartRate() > 120.0 || reading.getHeartRate() < 50.0)) {
            return "WARNING";
        }
        if (reading.getTemperature() != null && (reading.getTemperature() > 38.0 || reading.getTemperature() < 35.5)) {
            return "WARNING";
        }
        if (reading.getGsr() != null && reading.getGsr() > 10.0) {
            return "WARNING";
        }
        return "NORMAL";
    }

    private MonitoringSessionEntity startSession(PatientEntity patient, SensorReadingEntity reading) {
        MonitoringSessionEntity session = new MonitoringSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setPatientId(patient.getId());
        session.setDeviceId(reading.getDeviceId());
        session.setStartedAt(reading.getTimestamp());
        session.setStatus("ACTIVE");
        return session;
    }

    private AlertEntity createAlert(
            SensorReadingEntity reading,
            MlPredictionEntity prediction,
            MlDtos.PredictionResponse result,
            PatientEntity patient,
            UserEntity actor) {
        if (alerts.existsByPredictionId(prediction.getId())) return null;

        String severity = resolveSeverity(reading, result);
        String dedupKey = severity + ":" + patient.getId() + ":" + reading.getDeviceId();

        var existingActive = alerts.findFirstByDeduplicationKeyAndStatusInOrderByCreatedAtDesc(
                dedupKey, List.of("OPEN", "ACKNOWLEDGED"));
        if (existingActive.isPresent()) {
            AlertEntity activeAlert = existingActive.get();
            activeAlert.setValue(reading.getHeartRate() != null ? reading.getHeartRate() : activeAlert.getValue());
            activeAlert.setHeartRate(reading.getHeartRate());
            activeAlert.setGsr(reading.getGsr());
            activeAlert.setTemperature(reading.getTemperature());
            activeAlert.setTimestamp(result.timestamp());
            activeAlert = alerts.save(activeAlert);
            audit.record(actor.getId(), "ALERT_DEDUPLICATED", "ALERT", activeAlert.getId(),
                    Map.of("state", result.state(), "severity", severity));
            return activeAlert;
        }

        MlDtos.Contributor contributor = result.contributors() == null ? null : result.contributors().stream().findFirst().orElse(null);
        String parameter = contributor == null ? "MULTIVARIATE" : contributor.signal();
        double value = contributor == null || contributor.currentValue() == null
                ? (reading.getHeartRate() != null ? reading.getHeartRate() : 0.0)
                : contributor.currentValue();

        Double threshold = null;
        if (reading.getHeartRate() != null && reading.getHeartRate() > 140.0) {
            parameter = "BPM";
            value = reading.getHeartRate();
            threshold = 140.0;
        } else if (reading.getHeartRate() != null && reading.getHeartRate() < 45.0) {
            parameter = "BPM";
            value = reading.getHeartRate();
            threshold = 45.0;
        } else if (reading.getTemperature() != null && reading.getTemperature() > 39.5) {
            parameter = "Temperature";
            value = reading.getTemperature();
            threshold = 39.5;
        } else if (reading.getTemperature() != null && reading.getTemperature() < 34.0) {
            parameter = "Temperature";
            value = reading.getTemperature();
            threshold = 34.0;
        } else if (reading.getGsr() != null && reading.getGsr() > 12.0) {
            parameter = "GSR";
            value = reading.getGsr();
            threshold = 12.0;
        } else if (reading.getHeartRate() != null && reading.getHeartRate() > 120.0) {
            parameter = "BPM";
            value = reading.getHeartRate();
            threshold = 120.0;
        } else if (reading.getHeartRate() != null && reading.getHeartRate() < 50.0) {
            parameter = "BPM";
            value = reading.getHeartRate();
            threshold = 50.0;
        } else if (reading.getTemperature() != null && reading.getTemperature() > 38.0) {
            parameter = "Temperature";
            value = reading.getTemperature();
            threshold = 38.0;
        } else if (reading.getTemperature() != null && reading.getTemperature() < 35.5) {
            parameter = "Temperature";
            value = reading.getTemperature();
            threshold = 35.5;
        } else if (reading.getGsr() != null && reading.getGsr() > 10.0) {
            parameter = "GSR";
            value = reading.getGsr();
            threshold = 10.0;
        }

        String explanation = (result.reason() != null && !result.reason().isBlank())
                ? result.reason()
                : ("CRITICAL".equals(severity)
                    ? "Critical vital signs threshold or pattern detected; immediate clinical attention required."
                    : "Persistent statistical anomaly state or elevated vital threshold; requires clinical review.");

        AlertEntity alert = new AlertEntity();
        alert.setId(UUID.randomUUID().toString());
        alert.setPatientId(patient.getId());
        alert.setDeviceId(reading.getDeviceId());
        alert.setTimestamp(result.timestamp());
        alert.setSeverity(severity);
        alert.setMonitoringState(result.state());
        alert.setTrigger("CRITICAL".equals(severity) ? "SAFETY_AND_SCENARIO_MODEL" : "PERSISTENCE_POLICY");
        alert.setSource(reading.getSource());
        alert.setPredictionId(prediction.getId());
        alert.setParameter(parameter);
        alert.setValue(value);
        alert.setThreshold(threshold);
        alert.setAffectedSignals(json(result.contributors()));
        alert.setExplanation(explanation);
        alert.setStatus("OPEN");
        alert.setHeartRate(reading.getHeartRate());
        alert.setGsr(reading.getGsr());
        alert.setTemperature(reading.getTemperature());
        alert.setModelVersion(result.classifierVersion() != null ? result.classifierVersion() : result.modelVersion());
        alert.setModelScore(result.confidence() != null ? result.confidence() : result.anomalyScore());
        alert.setDeduplicationKey(dedupKey);
        alert = alerts.save(alert);

        NotificationEntity notification = new NotificationEntity();
        notification.setId(UUID.randomUUID().toString());
        notification.setAlertId(alert.getId());
        notification.setPatientId(patient.getId());
        notification.setSeverity(alert.getSeverity());
        notification.setParameter(alert.getParameter());
        notification.setValue(alert.getValue());
        notification.setTimestamp(alert.getTimestamp());
        notifications.save(notification);

        journey(patient, "ALERT", actor.getName(), explanation, result.timestamp(), severity);
        audit.record(actor.getId(), "ALERT_CREATED", "ALERT", alert.getId(),
                Map.of("state", result.state(), "severity", severity));
        return alert;
    }

    private void journey(PatientEntity patient, String type, String actor, String description, Instant timestamp, String severity) {
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

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Prediction metadata could not be serialized.", exception);
        }
    }
}
