package ai.careglove.vitals;

import ai.careglove.alerts.AlertDtos;
import ai.careglove.alerts.VitalThresholdEvaluationService;
import ai.careglove.audit.AuditService;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.ml.MlDtos;
import ai.careglove.ml.MlPredictionProcessingService;
import ai.careglove.ml.MlServiceClient;
import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.user.UserEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReadingIngestionService {
    private final SensorDataSource source;
    private final PatientAccessService patients;
    private final DeviceRepository devices;
    private final SensorReadingRepository readings;
    private final MlServiceClient ml;
    private final MlPredictionProcessingService processing;
    private final AuditService audit;
    private final PatientJourneyRepository journey;
    private final ContinuousMonitoringService monitoring;
    private final VitalThresholdEvaluationService thresholdEvaluation;

    public ReadingIngestionService(
            SensorDataSource source,
            PatientAccessService patients,
            DeviceRepository devices,
            SensorReadingRepository readings,
            MlServiceClient ml,
            MlPredictionProcessingService processing,
            AuditService audit,
            PatientJourneyRepository journey,
            ContinuousMonitoringService monitoring,
            VitalThresholdEvaluationService thresholdEvaluation) {
        this.source = source;
        this.patients = patients;
        this.devices = devices;
        this.readings = readings;
        this.ml = ml;
        this.processing = processing;
        this.audit = audit;
        this.journey = journey;
        this.monitoring = monitoring;
        this.thresholdEvaluation = thresholdEvaluation;
    }

    public IngestionResponse ingest(IngestReadingRequest request, UserEntity actor) {
        PatientEntity patient = patients.getVisible(request.patientId(), actor);
        DeviceEntity device = devices.findById(request.deviceId())
                .filter(candidate -> patient.getId().equals(candidate.getPatientId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Device is not assigned to this patient."));
        NormalizedSensorReading normalized = source.normalize(request, Instant.now());
        if (readings.findByPatientIdAndTimestamp(patient.getId(), normalized.timestamp()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A reading already exists for this patient and timestamp.");
        }
        SensorReadingEntity entity = new SensorReadingEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setPatientId(patient.getId());
        entity.setDeviceId(device.getId());
        entity.setTimestamp(normalized.timestamp());
        entity.setHeartRate(normalized.heartRate());
        entity.setGsr(normalized.gsr());
        entity.setTemperature(normalized.temperature());
        entity.setSource(normalized.source());
        entity.setReceivedAt(normalized.receivedAt());
        entity.setSignalQuality(normalized.signalQuality());
        entity.setFirmwareVersion(normalized.firmwareVersion());
        entity.setState("PENDING");
        entity = readings.saveAndFlush(entity);
        monitoring.bufferReading(entity);
        audit.record(actor.getId(), "VITAL_RECEIVED", "SENSOR_READING", entity.getId(), Map.of("source", entity.getSource()));

        // 1. Evaluate vital thresholds immediately on every incoming reading
        List<AlertDtos.AlertResponse> thresholdAlerts = thresholdEvaluation.evaluateReading(entity, patient, actor);
        AlertDtos.AlertResponse activeThresholdAlert = thresholdAlerts.isEmpty() ? null : thresholdAlerts.get(0);

        // 2. Query ML service if available
        try {
            MlDtos.PredictionResponse prediction = ml.predict(normalized);
            AlertDtos.AlertResponse mlAlert = processing.storePrediction(entity, prediction, actor);
            AlertDtos.AlertResponse finalAlert = activeThresholdAlert != null ? activeThresholdAlert : mlAlert;
            SensorReadingEntity stored = readings.findById(entity.getId()).orElse(entity);
            return new IngestionResponse(SensorReadingResponse.from(stored), prediction, finalAlert, "PROCESSED", null);
        } catch (RestClientException exception) {
            SensorReadingEntity stored = readings.findById(entity.getId()).orElse(entity);
            String state = activeThresholdAlert != null ? "PROCESSED" : "PENDING";
            String msg = activeThresholdAlert != null ? null : "Reading was saved; ML processing is unavailable and may be retried.";
            return new IngestionResponse(SensorReadingResponse.from(stored), null, activeThresholdAlert, state, msg);
        }
    }

    public IngestionResponse retry(String readingId, UserEntity actor) {
        SensorReadingEntity entity = readings.findById(readingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reading not found."));
        PatientEntity patient = patients.getVisible(entity.getPatientId(), actor);
        if (!"PENDING".equals(entity.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reading has already been processed.");
        }

        List<AlertDtos.AlertResponse> thresholdAlerts = thresholdEvaluation.evaluateReading(entity, patient, actor);
        AlertDtos.AlertResponse activeThresholdAlert = thresholdAlerts.isEmpty() ? null : thresholdAlerts.get(0);

        NormalizedSensorReading normalized = new NormalizedSensorReading(
                entity.getPatientId(), entity.getDeviceId(), entity.getTimestamp(), entity.getHeartRate(),
                entity.getGsr(), entity.getTemperature(), entity.getSource(),
                entity.getReceivedAt(), entity.getSignalQuality(), entity.getFirmwareVersion());
        try {
            MlDtos.PredictionResponse prediction = ml.predict(normalized);
            AlertDtos.AlertResponse mlAlert = processing.storePrediction(entity, prediction, actor);
            AlertDtos.AlertResponse finalAlert = activeThresholdAlert != null ? activeThresholdAlert : mlAlert;
            SensorReadingEntity stored = readings.findById(readingId).orElse(entity);
            return new IngestionResponse(SensorReadingResponse.from(stored), prediction, finalAlert, "PROCESSED", null);
        } catch (RestClientException exception) {
            SensorReadingEntity stored = readings.findById(readingId).orElse(entity);
            String state = activeThresholdAlert != null ? "PROCESSED" : "PENDING";
            return new IngestionResponse(
                    SensorReadingResponse.from(stored), null, activeThresholdAlert, state,
                    activeThresholdAlert != null ? null : "Reading remains saved; ML processing is unavailable and may be retried.");
        }
    }
}
