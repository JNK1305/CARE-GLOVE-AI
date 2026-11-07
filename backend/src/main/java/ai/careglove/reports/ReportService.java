package ai.careglove.reports;

import ai.careglove.alerts.AlertDtos;
import ai.careglove.alerts.AlertService;
import ai.careglove.audit.AuditService;
import ai.careglove.clinical.ClinicalRecordEntity;
import ai.careglove.clinical.ClinicalRecordRepository;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.journey.PatientJourneyEntity;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.user.UserEntity;
import ai.careglove.vitals.SensorReadingEntity;
import ai.careglove.vitals.SensorReadingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    private final ReportRepository reports;
    private final PatientAccessService patientAccess;
    private final SensorReadingRepository readings;
    private final AlertService alerts;
    private final ClinicalRecordRepository clinical;
    private final DeviceRepository devices;
    private final PatientJourneyRepository journey;
    private final AuditService audit;

    public ReportService(
            ReportRepository reports,
            PatientAccessService patientAccess,
            SensorReadingRepository readings,
            AlertService alerts,
            ClinicalRecordRepository clinical,
            DeviceRepository devices,
            PatientJourneyRepository journey,
            AuditService audit) {
        this.reports = reports;
        this.patientAccess = patientAccess;
        this.readings = readings;
        this.alerts = alerts;
        this.clinical = clinical;
        this.devices = devices;
        this.journey = journey;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.ReportResponse> list(UserEntity user) {
        List<String> ids = patientAccess.visibleTo(user).stream().map(PatientEntity::getId).toList();
        return ids.isEmpty() ? List.of()
                : reports.findByPatientIdInOrderByTimestampDesc(ids).stream().map(ReportDtos.ReportResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReportDtos.ReportResponse get(String id, UserEntity user) {
        ReportEntity report = reports.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found."));
        patientAccess.getVisible(report.getPatientId(), user);
        return ReportDtos.ReportResponse.from(report);
    }

    @Transactional
    public ReportDtos.ReportResponse generate(String patientId, UserEntity actor) {
        PatientEntity patient = patientAccess.getVisible(patientId, actor);
        List<SensorReadingEntity> history = readings.findByPatientIdOrderByTimestampDesc(patientId);
        List<AlertDtos.AlertResponse> patientAlerts = alerts.forPatient(patientId, actor);
        List<ClinicalRecordEntity> records = clinical.findByPatientIdOrderByTimestampDesc(patientId);
        DeviceEntity device = devices.findById(patient.getGlove()).orElse(null);
        Instant now = Instant.now();
        StringBuilder content = new StringBuilder()
                .append("Patient: ").append(patient.getId()).append(" · ").append(patient.getName()).append('\n')
                .append("Age: ").append(patient.getAge()).append('\n')
                .append("Ward / bed: ").append(patient.getWard()).append(" · ").append(patient.getBed()).append('\n')
                .append("Assigned glove: ").append(patient.getGlove()).append('\n')
                .append("Senior Doctor: ").append(patient.getDoctor().getName()).append('\n')
                .append("Lifecycle: ").append(patient.getLifecycle()).append('\n')
                .append("Current monitoring status: ").append(patient.getMonitoringStatus()).append('\n');
        if (patient.getGender() != null) content.append("Gender: ").append(patient.getGender()).append('\n');
        if (patient.getBloodGroup() != null) content.append("Blood group: ").append(patient.getBloodGroup()).append('\n');
        if (patient.getCondition() != null) content.append("Recorded condition: ").append(patient.getCondition()).append('\n');
        if (device != null) content.append("Device status: ").append(device.getStatus()).append('\n');
        if (history.isEmpty()) {
            content.append("No sensor readings have been stored.\n");
        } else {
            SensorReadingEntity sample = history.get(0);
            content.append("Latest reading: ").append(sample.getTimestamp()).append(" · ").append(sample.getSource()).append('\n')
                    .append("Heart rate: ").append(sample.getHeartRate())
                    .append(" · Temperature: ").append(sample.getTemperature()).append(" · GSR: ").append(sample.getGsr())
                    .append(" · monitoring state: ").append(sample.getState()).append('\n');
        }
        content.append("Alerts: ").append(patientAlerts.size()).append('\n');
        for (AlertDtos.AlertResponse alert : patientAlerts) {
            content.append(alert.timestamp()).append(' ').append(alert.severity()).append(' ')
                    .append(alert.monitoringState()).append(" · ").append(alert.parameter()).append(": ")
                    .append(alert.value()).append(" · ").append(alert.status()).append('\n');
        }
        content.append("Clinical records: ").append(records.size()).append('\n');
        for (ClinicalRecordEntity record : records) {
            content.append(record.getTimestamp()).append(' ').append(record.getType()).append(" · ")
                    .append(record.getContent() == null ? record.getTestName() : record.getContent()).append('\n');
        }
        ReportEntity report = new ReportEntity();
        report.setId(UUID.randomUUID().toString());
        report.setPatientId(patient.getId());
        report.setAuthorId(actor.getId());
        report.setAuthorName(actor.getName());
        report.setTimestamp(now);
        report.setTitle(patient.getName() + " monitoring report");
        report.setContent(content.toString());
        report = reports.save(report);
        PatientJourneyEntity event = new PatientJourneyEntity();
        event.setId(UUID.randomUUID().toString());
        event.setPatientId(patientId);
        event.setType("REPORT");
        event.setActor(actor.getName());
        event.setDescription("Generated patient monitoring report");
        event.setTimestamp(now);
        journey.save(event);
        audit.record(actor.getId(), "REPORT_GENERATED", "REPORT", report.getId(), Map.of("patientId", patientId));
        return ReportDtos.ReportResponse.from(report);
    }
}
