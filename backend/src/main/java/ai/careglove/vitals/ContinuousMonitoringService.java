package ai.careglove.vitals;

import ai.careglove.alerts.AlertEntity;
import ai.careglove.alerts.AlertRepository;
import ai.careglove.alerts.VitalThresholdEvaluationService;
import ai.careglove.audit.AuditService;
import ai.careglove.ml.MlDtos;
import ai.careglove.ml.MlPredictionEntity;
import ai.careglove.ml.MlPredictionProcessingService;
import ai.careglove.ml.MlPredictionRepository;
import ai.careglove.ml.MlServiceClient;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class ContinuousMonitoringService {
    private static final Logger log = LoggerFactory.getLogger(ContinuousMonitoringService.class);

    private final PatientRepository patients;
    private final SensorReadingRepository readings;
    private final MlServiceClient ml;
    private final MlPredictionProcessingService processing;
    private final MlPredictionRepository mlPredictions;
    private final AlertRepository alerts;
    private final AuditService audit;
    private final UserRepository users;
    private final VitalThresholdEvaluationService thresholdEvaluation;

    private final int intervalSeconds;
    private final int staleAfterSeconds;
    private final int windowDurationSeconds;
    private final int minReadingsForWindow;

    private final ConcurrentMap<String, Deque<SensorReadingEntity>> windowBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> activeEvaluations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PatientMonitoringStatus> statusMap = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public ContinuousMonitoringService(
            PatientRepository patients,
            SensorReadingRepository readings,
            MlServiceClient ml,
            MlPredictionProcessingService processing,
            MlPredictionRepository mlPredictions,
            AlertRepository alerts,
            AuditService audit,
            UserRepository users,
            VitalThresholdEvaluationService thresholdEvaluation,
            @Value("${careglove.monitoring.interval-seconds:15}") int configuredIntervalSeconds,
            @Value("${careglove.monitoring.stale-after-seconds:45}") int staleAfterSeconds,
            @Value("${careglove.monitoring.window-duration-seconds:60}") int windowDurationSeconds,
            @Value("${careglove.monitoring.min-readings:3}") int minReadingsForWindow) {
        this.patients = patients;
        this.readings = readings;
        this.ml = ml;
        this.processing = processing;
        this.mlPredictions = mlPredictions;
        this.alerts = alerts;
        this.audit = audit;
        this.users = users;
        this.thresholdEvaluation = thresholdEvaluation;

        // Enforce between 10 and 20 seconds as required by specification; default 15
        int clamped = Math.max(10, Math.min(20, configuredIntervalSeconds));
        this.intervalSeconds = clamped;
        this.staleAfterSeconds = Math.max(15, staleAfterSeconds);
        this.windowDurationSeconds = Math.max(30, windowDurationSeconds);
        this.minReadingsForWindow = Math.max(1, minReadingsForWindow);
        log.info("Initialized ContinuousMonitoringService with interval: {}s (configured: {}s), staleAfter: {}s",
                this.intervalSeconds, configuredIntervalSeconds, this.staleAfterSeconds);
    }

    @PostConstruct
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "careglove-continuous-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::evaluateAll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Continuous monitoring scheduler started successfully.");
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.info("Shutting down continuous monitoring scheduler...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Continuous monitoring scheduler shutdown complete.");
        }
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void bufferReading(SensorReadingEntity reading) {
        String key = reading.getPatientId() + ":" + reading.getDeviceId();
        Deque<SensorReadingEntity> buffer = windowBuffers.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        // Maintain order by timestamp
        buffer.addLast(reading);
        pruneBuffer(buffer, Instant.now());
        statusMap.remove(reading.getPatientId());
    }

    private void pruneBuffer(Deque<SensorReadingEntity> buffer, Instant now) {
        Instant cutoff = now.minusSeconds(Math.max(windowDurationSeconds, staleAfterSeconds) + 60L);
        while (buffer.size() > 1) {
            SensorReadingEntity first = buffer.peekFirst();
            if (first != null && first.getTimestamp().isBefore(cutoff)) {
                buffer.pollFirst();
            } else {
                break;
            }
        }
    }

    public void evaluateAll() {
        try {
            List<PatientEntity> activePatients = patients.findAll().stream()
                    .filter(p -> !"DISCHARGED".equalsIgnoreCase(p.getLifecycle()))
                    .toList();

            for (PatientEntity patient : activePatients) {
                String patientId = patient.getId();
                // Prevent overlapping evaluations for the same patient
                if (activeEvaluations.putIfAbsent(patientId, Boolean.TRUE) != null) {
                    continue;
                }
                try {
                    evaluatePatient(patient);
                } catch (Exception e) {
                    log.error("Error during scheduled evaluation for patient {}: {}", patientId, e.getMessage(), e);
                } finally {
                    activeEvaluations.remove(patientId);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in continuous monitoring tick: {}", e.getMessage(), e);
        }
    }

    public PatientMonitoringStatus evaluatePatient(PatientEntity patient) {
        Instant now = Instant.now();
        String deviceId = patient.getGlove();
        String key = patient.getId() + ":" + deviceId;

        Deque<SensorReadingEntity> buffer = windowBuffers.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        if (buffer.isEmpty()) {
            // Load latest readings from database
            List<SensorReadingEntity> recent = readings.findByPatientIdOrderByTimestampDesc(patient.getId());
            for (int i = Math.min(recent.size(), 10) - 1; i >= 0; i--) {
                buffer.add(recent.get(i));
            }
        }

        pruneBuffer(buffer, now);
        List<SensorReadingEntity> windowReadings = new ArrayList<>(buffer);

        if (windowReadings.isEmpty()) {
            patient.setMonitoringStatus("DISCONNECTED");
            patients.save(patient);
            PatientMonitoringStatus status = new PatientMonitoringStatus(
                    patient.getId(), deviceId, "DISCONNECTED", "NORMAL", "NORMAL",
                    null, new WindowStats(0, null, null, null), null, now, null,
                    "No sensor readings received. Sensor is disconnected or powered off.");
            statusMap.put(patient.getId(), status);
            return status;
        }

        SensorReadingEntity latestReading = windowReadings.get(windowReadings.size() - 1);
        Instant measurementTime = latestReading.getTimestamp();
        long ageSeconds = Duration.between(measurementTime, now).getSeconds();

        if (ageSeconds > staleAfterSeconds) {
            patient.setMonitoringStatus("STALE");
            if (latestReading.getHeartRate() != null) patient.setBpm(latestReading.getHeartRate());
            if (latestReading.getGsr() != null) patient.setGsr(latestReading.getGsr());
            if (latestReading.getTemperature() != null) patient.setTemp(latestReading.getTemperature());
            patients.save(patient);
            PatientMonitoringStatus status = new PatientMonitoringStatus(
                    patient.getId(), deviceId, "STALE", "NORMAL", "NORMAL",
                    SensorReadingResponse.from(latestReading),
                    new WindowStats(windowReadings.size(), null, null, null),
                    null, now, null,
                    "Sensor data is stale (" + ageSeconds + " seconds old). Sensor may be disconnected.");
            statusMap.put(patient.getId(), status);
            return status;
        }

        double sumBpm = 0, sumGsr = 0, sumTemp = 0;
        int validCount = 0;
        for (SensorReadingEntity r : windowReadings) {
            if (r.getHeartRate() != null && r.getGsr() != null && r.getTemperature() != null) {
                sumBpm += r.getHeartRate();
                sumGsr += r.getGsr();
                sumTemp += r.getTemperature();
                validCount++;
            }
        }

        if (validCount < minReadingsForWindow) {
            patient.setMonitoringStatus("INSUFFICIENT_DATA");
            patients.save(patient);
            PatientMonitoringStatus status = new PatientMonitoringStatus(
                    patient.getId(), deviceId, "INSUFFICIENT_DATA", "NORMAL", "NORMAL",
                    SensorReadingResponse.from(latestReading),
                    new WindowStats(validCount, null, null, null),
                    null, now, null,
                    "Window contains " + validCount + " readings, waiting for at least " + minReadingsForWindow + ".");
            statusMap.put(patient.getId(), status);
            return status;
        }

        double meanBpm = Math.round((sumBpm / validCount) * 100.0) / 100.0;
        double meanGsr = Math.round((sumGsr / validCount) * 1000.0) / 1000.0;
        double meanTemp = Math.round((sumTemp / validCount) * 100.0) / 100.0;
        WindowStats windowStats = new WindowStats(validCount, meanBpm, meanGsr, meanTemp);

        if (latestReading.getSignalQuality() != null && latestReading.getSignalQuality() < 0.20) {
            patient.setMonitoringStatus("INSUFFICIENT_DATA");
            patients.save(patient);
            PatientMonitoringStatus status = new PatientMonitoringStatus(
                    patient.getId(), deviceId, "INSUFFICIENT_DATA", "NORMAL", "NORMAL",
                    SensorReadingResponse.from(latestReading),
                    windowStats,
                    null, now, null,
                    "Inadequate sensor contact (signal quality " + latestReading.getSignalQuality() + "). Sensor leads off or detached.");
            statusMap.put(patient.getId(), status);
            return status;
        }

        MlDtos.PredictionResponse prediction = null;
        String modelError = null;
        AlertEntity activeAlert = null;

        UserEntity systemActor = users.findByRole(ai.careglove.common.Role.admin).stream().findFirst()
                .orElseGet(() -> users.findAll().stream().findFirst().orElse(null));

        // Always evaluate vital thresholds on the live latest reading
        var thresholdAlerts = thresholdEvaluation.evaluateReading(latestReading, patient, systemActor);
        if (!thresholdAlerts.isEmpty()) {
            activeAlert = alerts.findById(thresholdAlerts.get(0).id()).orElse(null);
        }

        // Check if latest reading already has an ML prediction or has already been processed
        Optional<MlPredictionEntity> existingPred = mlPredictions.findByReadingId(latestReading.getId());
        if (existingPred.isPresent()) {
            MlPredictionEntity p = existingPred.get();
            prediction = new MlDtos.PredictionResponse(
                    p.getPatientId(),
                    p.getTimestamp(),
                    p.getState(),
                    p.getAnomalyScore(),
                    p.getModelVersion(),
                    List.of(),
                    new MlDtos.DataQuality(p.isDataQualityValid(), List.of(), List.of(), 1),
                    p.isScoredWindow());
            if (activeAlert == null) {
                activeAlert = alerts.findFirstByPatientIdAndStatusInOrderByCreatedAtDesc(
                        patient.getId(), List.of("OPEN", "ACKNOWLEDGED")).orElse(null);
            }
        } else if (!"PENDING".equals(latestReading.getState()) && latestReading.getState() != null) {
            prediction = new MlDtos.PredictionResponse(
                    latestReading.getPatientId(),
                    latestReading.getTimestamp(),
                    latestReading.getState(),
                    null,
                    "v1.0",
                    List.of(),
                    new MlDtos.DataQuality(true, List.of(), List.of(), 1),
                    true);
            if (activeAlert == null) {
                activeAlert = alerts.findFirstByPatientIdAndStatusInOrderByCreatedAtDesc(
                        patient.getId(), List.of("OPEN", "ACKNOWLEDGED")).orElse(null);
            }
        } else {
            // Unprocessed/pending reading: query ML service
            NormalizedSensorReading normalized = new NormalizedSensorReading(
                    latestReading.getPatientId(), latestReading.getDeviceId(), latestReading.getTimestamp(),
                    latestReading.getHeartRate(), latestReading.getGsr(), latestReading.getTemperature(),
                    latestReading.getSource(), latestReading.getReceivedAt(), latestReading.getSignalQuality(),
                    latestReading.getFirmwareVersion());
            try {
                prediction = ml.predict(normalized);
                if (systemActor != null) {
                    var alertResp = processing.storePrediction(latestReading, prediction, systemActor);
                    if (alertResp != null && activeAlert == null) {
                        activeAlert = alerts.findById(alertResp.id()).orElse(null);
                    }
                }
            } catch (org.springframework.web.client.HttpStatusCodeException ex) {
                if (ex.getStatusCode().value() == 409) {
                    // Reading was already scored by ML service in memory
                    Optional<MlPredictionEntity> retryPred = mlPredictions.findByReadingId(latestReading.getId());
                    if (retryPred.isPresent()) {
                        MlPredictionEntity p = retryPred.get();
                        prediction = new MlDtos.PredictionResponse(
                                p.getPatientId(), p.getTimestamp(), p.getState(), p.getAnomalyScore(),
                                p.getModelVersion(), List.of(),
                                new MlDtos.DataQuality(p.isDataQualityValid(), List.of(), List.of(), 1),
                                p.isScoredWindow());
                    } else {
                        prediction = new MlDtos.PredictionResponse(
                                latestReading.getPatientId(), latestReading.getTimestamp(),
                                latestReading.getState() != null && !"PENDING".equals(latestReading.getState()) ? latestReading.getState() : "MONITOR",
                                null, "v1.0", List.of(),
                                new MlDtos.DataQuality(true, List.of(), List.of(), 1), false);
                    }
                } else {
                    modelError = "ML inference service is temporarily unreachable: " + ex.getMessage();
                    log.warn("ML inference service unreachable for patient {}: {}", patient.getId(), ex.getMessage());
                }
            } catch (RestClientException ex) {
                modelError = "ML inference service is temporarily unreachable: " + ex.getMessage();
                log.warn("ML inference service unreachable for patient {}: {}", patient.getId(), ex.getMessage());
            }
        }

        String severity;
        String explanation;
        if (prediction != null) {
            severity = processing.resolveSeverity(latestReading, prediction);
            explanation = prediction.reason() != null ? prediction.reason() : "Window evaluated by scenario model.";
        } else {
            severity = evaluateSafetyRulesOnly(latestReading);
            explanation = modelError != null ? modelError : "Vital threshold safety evaluation.";
        }

        if (activeAlert == null) {
            activeAlert = alerts.findFirstByPatientIdAndStatusInOrderByCreatedAtDesc(
                    patient.getId(), List.of("OPEN", "ACKNOWLEDGED")).orElse(null);
        }

        // If an active alert exists, ensure severity matches or exceeds it
        if (activeAlert != null && ("CRITICAL".equalsIgnoreCase(activeAlert.getSeverity()) || "CRITICAL".equalsIgnoreCase(severity))) {
            severity = "CRITICAL";
        } else if (activeAlert != null && ("WARNING".equalsIgnoreCase(activeAlert.getSeverity()) || "WARNING".equalsIgnoreCase(severity))) {
            severity = "WARNING";
        }

        patient.setMonitoringStatus(modelError != null ? "MODEL_ERROR" : "ACTIVE");
        patient.setBpm(latestReading.getHeartRate());
        patient.setGsr(latestReading.getGsr());
        patient.setTemp(latestReading.getTemperature());
        if ("CRITICAL".equals(severity)) {
            patient.setStatus("Critical");
        } else if ("WARNING".equals(severity)) {
            if (!"Critical".equals(patient.getStatus())) patient.setStatus("Watch");
        } else {
            if (!"Critical".equals(patient.getStatus())) patient.setStatus("Stable");
        }
        patients.save(patient);

        String healthState = "CRITICAL".equals(severity) ? "CRITICAL" : ("WARNING".equals(severity) ? "WARNING" : "NORMAL");
        PatientMonitoringStatus status = new PatientMonitoringStatus(
                patient.getId(), deviceId, patient.getMonitoringStatus(), healthState, severity,
                SensorReadingResponse.from(latestReading), windowStats,
                prediction != null ? PredictionSummary.from(prediction) : null,
                now, activeAlert != null ? activeAlert.getId() : null, explanation);

        statusMap.put(patient.getId(), status);
        return status;
    }

    private String evaluateSafetyRulesOnly(SensorReadingEntity reading) {
        if (reading.getHeartRate() != null && (reading.getHeartRate() > 140.0 || reading.getHeartRate() < 45.0)) {
            return "CRITICAL";
        }
        if (reading.getTemperature() != null && (reading.getTemperature() > 39.5 || reading.getTemperature() < 34.0)) {
            return "CRITICAL";
        }
        if (reading.getGsr() != null && reading.getGsr() > 12.0) {
            return "CRITICAL";
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

    public PatientMonitoringStatus getStatus(String patientId) {
        PatientMonitoringStatus cached = statusMap.get(patientId);
        if (cached != null) {
            return cached;
        }
        PatientEntity patient = patients.findById(patientId).orElse(null);
        if (patient != null) {
            return evaluatePatient(patient);
        }
        return null;
    }

    public record WindowStats(
            int readingCount,
            Double meanHeartRate,
            Double meanGsr,
            Double meanTemperature) {}

    public record PredictionSummary(
            String severity,
            String state,
            String modelVersion,
            Double confidence,
            Double anomalyScore,
            String reason,
            Instant timestamp) {
        public static PredictionSummary from(MlDtos.PredictionResponse r) {
            return new PredictionSummary(
                    r.severity(), r.state(),
                    r.classifierVersion() != null ? r.classifierVersion() : r.modelVersion(),
                    r.confidence(), r.anomalyScore(), r.reason(), r.timestamp());
        }
    }

    public record PatientMonitoringStatus(
            String patientId,
            String deviceId,
            String monitoringStatus,
            String healthState,
            String severity,
            SensorReadingResponse latestReading,
            WindowStats windowStats,
            PredictionSummary latestPrediction,
            Instant evaluatedAt,
            String activeAlertId,
            String message) {}
}
