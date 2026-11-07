package ai.careglove.vitals;

import ai.careglove.patient.PatientAccessService;
import ai.careglove.user.UserEntity;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class VitalsController {
    private final ReadingIngestionService ingestion;
    private final SensorReadingRepository readings;
    private final MonitoringSessionRepository sessions;
    private final PatientAccessService patientAccess;
    private final ContinuousMonitoringService monitoring;

    public VitalsController(
            ReadingIngestionService ingestion,
            SensorReadingRepository readings,
            MonitoringSessionRepository sessions,
            PatientAccessService patientAccess,
            ContinuousMonitoringService monitoring) {
        this.ingestion = ingestion;
        this.readings = readings;
        this.sessions = sessions;
        this.patientAccess = patientAccess;
        this.monitoring = monitoring;
    }

    @PostMapping("/api/v1/vitals/ingest")
    public ResponseEntity<IngestionResponse> ingest(
            @Valid @RequestBody IngestReadingRequest request,
            @AuthenticationPrincipal UserEntity actor) {
        IngestionResponse response = ingestion.ingest(request, actor);
        return ResponseEntity.status("PENDING".equals(response.processingStatus()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/api/v1/vitals/{readingId}/process")
    public ResponseEntity<IngestionResponse> retry(
            @PathVariable String readingId,
            @AuthenticationPrincipal UserEntity actor) {
        IngestionResponse response = ingestion.retry(readingId, actor);
        return ResponseEntity.status("PENDING".equals(response.processingStatus()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(response);
    }

    @GetMapping("/api/patients/{patientId}/vitals")
    public List<SensorReadingResponse> readings(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        patientAccess.getVisible(patientId, user);
        return readings.findByPatientIdOrderByTimestampDesc(patientId).stream().map(SensorReadingResponse::from).toList();
    }

    @GetMapping("/api/patients/{patientId}/monitoring-status")
    public ContinuousMonitoringService.PatientMonitoringStatus monitoringStatus(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        patientAccess.getVisible(patientId, user);
        return monitoring.getStatus(patientId);
    }

    @GetMapping("/api/system/status")
    public SystemStatusResponse systemStatus() {
        return new SystemStatusResponse(
                "UP",
                "UP",
                monitoring.getIntervalSeconds(),
                15,
                List.of("heart_rate", "gsr", "temperature"));
    }

    @GetMapping("/api/patients/{patientId}/monitoring-sessions")
    public List<MonitoringSessionResponse> sessions(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        patientAccess.getVisible(patientId, user);
        return sessions.findAll().stream()
                .filter(session -> session.getPatientId().equals(patientId))
                .map(MonitoringSessionResponse::from)
                .toList();
    }

    public record SystemStatusResponse(
            String backendStatus,
            String databaseStatus,
            int monitoringIntervalSeconds,
            int defaultIntervalSeconds,
            List<String> requiredFeatures) {}

    public record MonitoringSessionResponse(
            String id,
            String patientId,
            String deviceId,
            java.time.Instant startedAt,
            java.time.Instant endedAt,
            String status,
            boolean active,
            java.time.Instant recoveredAt) {
        static MonitoringSessionResponse from(MonitoringSessionEntity session) {
            return new MonitoringSessionResponse(session.getId(), session.getPatientId(), session.getDeviceId(),
                    session.getStartedAt(), session.getEndedAt(), session.getStatus(), session.isActive(), session.getRecoveredAt());
        }
    }
}
