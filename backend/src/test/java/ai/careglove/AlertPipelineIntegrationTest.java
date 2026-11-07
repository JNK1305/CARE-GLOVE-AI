package ai.careglove;

import ai.careglove.alerts.*;
import ai.careglove.common.Role;
import ai.careglove.device.*;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.patient.*;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import ai.careglove.vitals.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:careglove_alert_pipeline_test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,CONDITION,READ,TRIGGER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "careglove.jwt.secret=care-glove-test-secret-at-least-32-bytes-long",
        "careglove.monitoring.interval-seconds=15",
        "careglove.monitoring.stale-after-seconds=45"
})
class AlertPipelineIntegrationTest {

    @Autowired ReadingIngestionService ingestionService;
    @Autowired VitalThresholdEvaluationService thresholdService;
    @Autowired AlertService alertService;
    @Autowired AlertRepository alerts;
    @Autowired NotificationRepository notifications;
    @Autowired PatientJourneyRepository journey;
    @Autowired AdmissionRepository admissions;
    @Autowired SensorReadingRepository readings;
    @Autowired PatientRepository patients;
    @Autowired DeviceRepository devices;
    @Autowired BedRepository beds;
    @Autowired WardRepository wards;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PatientService patientService;

    private UserEntity admin;
    private UserEntity doctor;
    private PatientEntity patient;

    @BeforeEach
    void setup() {
        notifications.deleteAll();
        alerts.deleteAll();
        readings.deleteAll();
        journey.deleteAll();
        admissions.deleteAll();
        patients.deleteAll();
        devices.deleteAll();
        beds.deleteAll();
        wards.deleteAll();
        users.deleteAll();

        WardEntity ward = new WardEntity();
        ward.setId("ICU");
        ward.setName("ICU");
        wards.save(ward);

        BedEntity bed = new BedEntity();
        bed.setId("ICU:B-01");
        bed.setWardId("ICU");
        bed.setLabel("B-01");
        bed.setGloveId("GLV-ICU-001");
        beds.save(bed);

        DeviceEntity dev = new DeviceEntity();
        dev.setId("GLV-ICU-001");
        dev.setWard("ICU");
        dev.setBed("B-01");
        devices.save(dev);

        doctor = new UserEntity();
        doctor.setId("doc-pipeline");
        doctor.setEmail("doctor@careglove.demo");
        doctor.setName("Dr. Amit Sharma");
        doctor.setRole(Role.senior);
        doctor.setPasswordHash(passwords.encode("test-pass-123"));
        users.save(doctor);

        admin = new UserEntity();
        admin.setId("admin-pipeline");
        admin.setEmail("admin@careglove.demo");
        admin.setName("Admin Tester");
        admin.setRole(Role.admin);
        admin.setPasswordHash(passwords.encode("test-pass-123"));
        users.save(admin);

        var patientDto = patientService.create(new PatientDtos.CreatePatientRequest(
                "Aarav Patel", "45 yrs", "ICU", "B-01", "GLV-ICU-001", "doc-pipeline",
                "Post-cardiac monitoring", "Male", "+91 98765 43210", "+91 98765 43211", "O+",
                "2026-11-02", "09:00", true), admin);
        patient = patients.findById(patientDto.id()).orElseThrow();
    }

    @Test
    @DisplayName("Case A: Normal readings produce NO abnormal alerts")
    void testCaseA_NormalReadings_NoAlerts() {
        IngestReadingRequest req = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", Instant.now(),
                72.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");

        IngestionResponse response = ingestionService.ingest(req, admin);

        assertThat(response.alert()).isNull();
        assertThat(alerts.findAll()).isEmpty();
        assertThat(notifications.findAll()).isEmpty();

        PatientEntity updatedPatient = patients.findById(patient.getId()).orElseThrow();
        assertThat(updatedPatient.getStatus()).isEqualTo("Stable");
        assertThat(updatedPatient.getBpm()).isEqualTo(72.0);
        assertThat(updatedPatient.getTemp()).isEqualTo(36.8);
        assertThat(updatedPatient.getGsr()).isEqualTo(3.5);
    }

    @Test
    @DisplayName("Case B: High heart rate produces critical tachycardia alert")
    void testCaseB_HighHeartRate_CriticalAlert() {
        IngestReadingRequest req = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", Instant.now(),
                155.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");

        IngestionResponse response = ingestionService.ingest(req, admin);

        assertThat(response.alert()).isNotNull();
        assertThat(response.alert().severity()).isEqualTo("CRITICAL");
        assertThat(response.alert().parameter()).isEqualTo("BPM");
        assertThat(response.alert().value()).isEqualTo(155.0);
        assertThat(response.alert().threshold()).isEqualTo(140.0);
        assertThat(response.alert().status()).isEqualTo("OPEN");

        List<AlertEntity> stored = alerts.findByPatientIdOrderByTimestampDesc(patient.getId());
        assertThat(stored).hasSize(1);
        AlertEntity alertEntity = stored.get(0);
        assertThat(alertEntity.getSeverity()).isEqualTo("CRITICAL");
        assertThat(alertEntity.getHeartRate()).isEqualTo(155.0);
        assertThat(alertEntity.getStatus()).isEqualTo("OPEN");

        // Verified via alert service / REST API layer
        List<AlertDtos.AlertResponse> patientAlerts = alertService.forPatient(patient.getId(), admin);
        assertThat(patientAlerts).hasSize(1);
        assertThat(patientAlerts.get(0).severity()).isEqualTo("CRITICAL");

        // Verified notification created
        assertThat(notifications.findAll()).hasSize(1);

        // Verified patient status escalated to Critical
        PatientEntity updatedPatient = patients.findById(patient.getId()).orElseThrow();
        assertThat(updatedPatient.getStatus()).isEqualTo("Critical");
    }

    @Test
    @DisplayName("Case C: Low heart rate produces critical bradycardia alert")
    void testCaseC_LowHeartRate_CriticalAlert() {
        IngestReadingRequest req = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", Instant.now(),
                38.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");

        IngestionResponse response = ingestionService.ingest(req, admin);

        assertThat(response.alert()).isNotNull();
        assertThat(response.alert().severity()).isEqualTo("CRITICAL");
        assertThat(response.alert().parameter()).isEqualTo("BPM");
        assertThat(response.alert().value()).isEqualTo(38.0);
        assertThat(response.alert().threshold()).isEqualTo(45.0);

        List<AlertEntity> stored = alerts.findByPatientIdOrderByTimestampDesc(patient.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getExplanation()).contains("bradycardia");

        PatientEntity updatedPatient = patients.findById(patient.getId()).orElseThrow();
        assertThat(updatedPatient.getStatus()).isEqualTo("Critical");
    }

    @Test
    @DisplayName("Case D: Abnormal temperature produces hyperpyrexia alert")
    void testCaseD_AbnormalTemperature_CriticalAlert() {
        IngestReadingRequest req = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", Instant.now(),
                78.0, 3.5, 40.2, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");

        IngestionResponse response = ingestionService.ingest(req, admin);

        assertThat(response.alert()).isNotNull();
        assertThat(response.alert().severity()).isEqualTo("CRITICAL");
        assertThat(response.alert().parameter()).isEqualTo("Temperature");
        assertThat(response.alert().value()).isEqualTo(40.2);
        assertThat(response.alert().threshold()).isEqualTo(39.5);

        List<AlertEntity> stored = alerts.findByPatientIdOrderByTimestampDesc(patient.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getParameter()).isEqualTo("Temperature");

        PatientEntity updatedPatient = patients.findById(patient.getId()).orElseThrow();
        assertThat(updatedPatient.getStatus()).isEqualTo("Critical");
    }

    @Test
    @DisplayName("Case E: Abnormal GSR produces skin conductance alert")
    void testCaseE_AbnormalGSR_Alert() {
        IngestReadingRequest req = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", Instant.now(),
                75.0, 14.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");

        IngestionResponse response = ingestionService.ingest(req, admin);

        assertThat(response.alert()).isNotNull();
        assertThat(response.alert().severity()).isEqualTo("CRITICAL");
        assertThat(response.alert().parameter()).isEqualTo("GSR");
        assertThat(response.alert().value()).isEqualTo(14.5);
        assertThat(response.alert().threshold()).isEqualTo(12.0);

        List<AlertEntity> stored = alerts.findByPatientIdOrderByTimestampDesc(patient.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getParameter()).isEqualTo("GSR");

        PatientEntity updatedPatient = patients.findById(patient.getId()).orElseThrow();
        assertThat(updatedPatient.getStatus()).isEqualTo("Critical");
    }

    @Test
    @DisplayName("Case F: Normal readings after an alert resolves open alert")
    void testCaseF_Recovery_ResolvesAlert() {
        Instant t1 = Instant.now().minusSeconds(30);
        IngestReadingRequest reqAbnormal = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", t1,
                150.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");
        ingestionService.ingest(reqAbnormal, admin);

        List<AlertEntity> openBefore = alerts.findByPatientIdAndStatusInOrderByCreatedAtDesc(
                patient.getId(), List.of("OPEN"));
        assertThat(openBefore).hasSize(1);
        assertThat(openBefore.get(0).getStatus()).isEqualTo("OPEN");

        // Normal reading arrives 15s later
        Instant t2 = Instant.now();
        IngestReadingRequest reqNormal = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", t2,
                75.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");
        ingestionService.ingest(reqNormal, admin);

        List<AlertEntity> openAfter = alerts.findByPatientIdAndStatusInOrderByCreatedAtDesc(
                patient.getId(), List.of("OPEN"));
        assertThat(openAfter).isEmpty();

        List<AlertEntity> allAlerts = alerts.findByPatientIdOrderByTimestampDesc(patient.getId());
        assertThat(allAlerts).hasSize(1);
        assertThat(allAlerts.get(0).getStatus()).isEqualTo("RESOLVED");
        assertThat(allAlerts.get(0).getResolvedAt()).isNotNull();

        // Patient status recovers to Stable
        PatientEntity updatedPatient = patients.findById(patient.getId()).orElseThrow();
        assertThat(updatedPatient.getStatus()).isEqualTo("Stable");

        // Recovery event recorded in patient journey
        var journeyEvents = journey.findByPatientIdOrderByTimestampDesc(patient.getId());
        assertThat(journeyEvents.stream().anyMatch(j -> "RECOVERY".equals(j.getType()))).isTrue();
    }

    @Test
    @DisplayName("Case G: Repeated abnormal readings are deduplicated")
    void testCaseG_DuplicateReadings_Deduplicated() {
        Instant t1 = Instant.now().minusSeconds(15);
        IngestReadingRequest req1 = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", t1,
                155.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");
        ingestionService.ingest(req1, admin);

        assertThat(alerts.findAll()).hasSize(1);
        AlertEntity initial = alerts.findAll().get(0);
        String initialAlertId = initial.getId();

        // 15 seconds later, another abnormal reading arrives for same patient/parameter
        Instant t2 = Instant.now();
        IngestReadingRequest req2 = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", t2,
                160.0, 3.5, 36.8, "FIREBASE_RTDB_LIVE", 0.95, "v1.0");
        ingestionService.ingest(req2, admin);

        // Deduplication: no duplicate alert created!
        List<AlertEntity> afterDuplicate = alerts.findAll();
        assertThat(afterDuplicate).hasSize(1);
        assertThat(afterDuplicate.get(0).getId()).isEqualTo(initialAlertId);
        assertThat(afterDuplicate.get(0).getValue()).isEqualTo(160.0);
        assertThat(afterDuplicate.get(0).getHeartRate()).isEqualTo(160.0);
    }

    @Test
    @DisplayName("Demo sensor readings do NOT generate real alerts")
    void testDemoReadingsDoNotGenerateAlerts() {
        IngestReadingRequest reqDemo = new IngestReadingRequest(
                patient.getId(), "GLV-ICU-001", Instant.now(),
                180.0, 15.0, 41.0, "DEMO_SENSOR", 0.95, "v1.0");

        IngestionResponse response = ingestionService.ingest(reqDemo, admin);

        assertThat(response.alert()).isNull();
        assertThat(alerts.findAll()).isEmpty();
    }
}
