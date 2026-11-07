package ai.careglove;

import ai.careglove.alerts.AlertDtos;
import ai.careglove.alerts.AlertEntity;
import ai.careglove.alerts.AlertRepository;
import ai.careglove.common.Role;
import ai.careglove.device.BedEntity;
import ai.careglove.device.BedRepository;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.device.WardEntity;
import ai.careglove.device.WardRepository;
import ai.careglove.ml.MlDtos;
import ai.careglove.ml.MlPredictionProcessingService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.patient.PatientService;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import ai.careglove.vitals.ContinuousMonitoringService;
import ai.careglove.vitals.SensorReadingEntity;
import ai.careglove.vitals.SensorReadingRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:careglove_mon_test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,CONDITION,READ,TRIGGER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "careglove.jwt.secret=care-glove-test-secret-at-least-32-bytes-long",
        "careglove.ml.persistence-windows=1",
        "careglove.ml.recovery-windows=1",
        "careglove.monitoring.interval-seconds=15",
        "careglove.monitoring.stale-after-seconds=45"
})
class ContinuousMonitoringAndAlertTest {
    @Autowired ContinuousMonitoringService monitoringService;
    @Autowired MlPredictionProcessingService predictionProcessing;
    @Autowired PatientRepository patients;
    @Autowired DeviceRepository devices;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired SensorReadingRepository readings;
    @Autowired AlertRepository alerts;
    @Autowired ai.careglove.alerts.NotificationRepository notifications;
    @Autowired ai.careglove.patient.AdmissionRepository admissions;
    @Autowired ai.careglove.journey.PatientJourneyRepository journey;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PatientService patientService;

    private UserEntity doctor;
    private UserEntity admin;
    private PatientEntity patientA;
    private PatientEntity patientB;

    @BeforeEach
    void setupTestData() {
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

        BedEntity bed1 = new BedEntity();
        bed1.setId("ICU:B-01");
        bed1.setWardId("ICU");
        bed1.setLabel("B-01");
        bed1.setGloveId("GLV-ICU-001");
        beds.save(bed1);

        BedEntity bed2 = new BedEntity();
        bed2.setId("ICU:B-02");
        bed2.setWardId("ICU");
        bed2.setLabel("B-02");
        bed2.setGloveId("GLV-ICU-002");
        beds.save(bed2);

        DeviceEntity dev1 = new DeviceEntity();
        dev1.setId("GLV-ICU-001");
        dev1.setWard("ICU");
        dev1.setBed("B-01");
        devices.save(dev1);

        DeviceEntity dev2 = new DeviceEntity();
        dev2.setId("GLV-ICU-002");
        dev2.setWard("ICU");
        dev2.setBed("B-02");
        devices.save(dev2);

        doctor = new UserEntity();
        doctor.setId("doc-1");
        doctor.setEmail("doc1@careglove.test");
        doctor.setName("Dr. One");
        doctor.setRole(Role.senior);
        doctor.setPasswordHash(passwords.encode("secure-test-pass-123"));
        users.save(doctor);

        admin = new UserEntity();
        admin.setId("admin-1");
        admin.setEmail("admin1@careglove.test");
        admin.setName("Admin");
        admin.setRole(Role.admin);
        admin.setPasswordHash(passwords.encode("secure-test-pass-123"));
        users.save(admin);

        var respA = patientService.create(new ai.careglove.patient.PatientDtos.CreatePatientRequest(
                "Patient Alpha", "50 yrs", "ICU", "B-01", "GLV-ICU-001", "doc-1",
                "Cardiac observation", "Other", null, null, null, "2026-10-13", "10:00", true), admin);
        patientA = patients.findById(respA.id()).orElseThrow();

        var respB = patientService.create(new ai.careglove.patient.PatientDtos.CreatePatientRequest(
                "Patient Beta", "60 yrs", "ICU", "B-02", "GLV-ICU-002", "doc-1",
                "Post-op observation", "Other", null, null, null, "2026-10-13", "10:00", true), admin);
        patientB = patients.findById(respB.id()).orElseThrow();
    }

    @Test
    void monitoringIntervalIsConfiguredCorrectly() {
        assertThat(monitoringService.getIntervalSeconds()).isEqualTo(15);
    }

    @Test
    void rollingWindowsRemainSeparatedByPatientAndDevice() {
        Instant t0 = Instant.now().minusSeconds(30);

        // Add 3 readings for Patient A
        for (int i = 0; i < 3; i++) {
            SensorReadingEntity rA = new SensorReadingEntity();
            rA.setId("read-a-" + i);
            rA.setPatientId(patientA.getId());
            rA.setDeviceId("GLV-ICU-001");
            rA.setTimestamp(t0.plusSeconds(i * 10));
            rA.setHeartRate(75.0 + i);
            rA.setGsr(1.5);
            rA.setTemperature(36.6);
            rA.setSource("SIMULATED");
            rA.setReceivedAt(Instant.now());
            readings.save(rA);
            monitoringService.bufferReading(rA);
        }

        // Add 3 readings for Patient B with different values
        for (int i = 0; i < 3; i++) {
            SensorReadingEntity rB = new SensorReadingEntity();
            rB.setId("read-b-" + i);
            rB.setPatientId(patientB.getId());
            rB.setDeviceId("GLV-ICU-002");
            rB.setTimestamp(t0.plusSeconds(i * 10));
            rB.setHeartRate(110.0 + i);
            rB.setGsr(4.0);
            rB.setTemperature(37.5);
            rB.setSource("SIMULATED");
            rB.setReceivedAt(Instant.now());
            readings.save(rB);
            monitoringService.bufferReading(rB);
        }

        var statusA = monitoringService.evaluatePatient(patientA);
        var statusB = monitoringService.evaluatePatient(patientB);

        assertThat(statusA.patientId()).isEqualTo(patientA.getId());
        assertThat(statusA.deviceId()).isEqualTo("GLV-ICU-001");
        assertThat(statusA.windowStats().readingCount()).isEqualTo(3);
        assertThat(statusA.windowStats().meanHeartRate()).isBetween(75.0, 77.0);

        assertThat(statusB.patientId()).isEqualTo(patientB.getId());
        assertThat(statusB.deviceId()).isEqualTo("GLV-ICU-002");
        assertThat(statusB.windowStats().readingCount()).isEqualTo(3);
        assertThat(statusB.windowStats().meanHeartRate()).isBetween(110.0, 112.0);
    }

    @Test
    void missingAndStaleDataReportedSeparatelyFromHealthyStatus() {
        // Patient with no readings is DISCONNECTED, not NORMAL
        PatientEntity emptyPatient = patientA;
        var statusDisconnected = monitoringService.evaluatePatient(emptyPatient);
        assertThat(statusDisconnected.monitoringStatus()).isEqualTo("DISCONNECTED");
        assertThat(statusDisconnected.healthState()).isEqualTo("NORMAL");

        // Patient with stale readings is STALE
        Instant staleTime = Instant.now().minusSeconds(120);
        SensorReadingEntity stale = new SensorReadingEntity();
        stale.setId("stale-1");
        stale.setPatientId(patientA.getId());
        stale.setDeviceId("GLV-ICU-001");
        stale.setTimestamp(staleTime);
        stale.setHeartRate(72.0);
        stale.setGsr(2.0);
        stale.setTemperature(36.5);
        stale.setSource("SIMULATED");
        stale.setReceivedAt(staleTime);
        readings.save(stale);
        monitoringService.bufferReading(stale);

        var statusStale = monitoringService.evaluatePatient(patientA);
        assertThat(statusStale.monitoringStatus()).isEqualTo("STALE");

        // Patient with only 1 recent reading is INSUFFICIENT_DATA
        SensorReadingEntity recentOne = new SensorReadingEntity();
        recentOne.setId("recent-one");
        recentOne.setPatientId(patientB.getId());
        recentOne.setDeviceId("GLV-ICU-002");
        recentOne.setTimestamp(Instant.now());
        recentOne.setHeartRate(72.0);
        recentOne.setGsr(2.0);
        recentOne.setTemperature(36.5);
        recentOne.setSource("SIMULATED");
        recentOne.setReceivedAt(Instant.now());
        readings.save(recentOne);
        monitoringService.bufferReading(recentOne);

        var statusInsufficient = monitoringService.evaluatePatient(patientB);
        assertThat(statusInsufficient.monitoringStatus()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void criticalAlertPersistenceAndDuplicateSuppression() {
        Instant measurementTime1 = Instant.now().minusSeconds(15);
        SensorReadingEntity critical1 = new SensorReadingEntity();
        critical1.setId("crit-read-1");
        critical1.setPatientId(patientA.getId());
        critical1.setDeviceId("GLV-ICU-001");
        critical1.setTimestamp(measurementTime1);
        critical1.setHeartRate(155.0);
        critical1.setGsr(8.5);
        critical1.setTemperature(39.8);
        critical1.setSource("SIMULATED");
        critical1.setReceivedAt(measurementTime1);
        critical1.setState("PENDING");
        readings.saveAndFlush(critical1);

        MlDtos.PredictionResponse pred1 = new MlDtos.PredictionResponse(
                patientA.getId(), measurementTime1, "POTENTIAL_ANOMALY", 9.5, "synthetic-scenario-v1",
                List.of(new MlDtos.Contributor("heart_rate", "ABOVE_BASELINE", 155.0, 75.0, 80.0, 9.5)),
                new MlDtos.DataQuality(true, List.of(), List.of(), 4), true,
                "CRITICAL", "High heart rate and temperature pattern detected", 0.95, "synthetic-scenario-v1");

        // First critical event creates alert
        var alert1 = predictionProcessing.storePrediction(critical1, pred1, admin);
        assertThat(alert1).isNotNull();
        assertThat(alert1.severity()).isEqualTo("CRITICAL");
        assertThat(alerts.findAll()).hasSize(1);
        AlertEntity savedAlert1 = alerts.findAll().get(0);
        assertThat(savedAlert1.getStatus()).isEqualTo("OPEN");
        assertThat(savedAlert1.getHeartRate()).isEqualTo(155.0);
        assertThat(savedAlert1.getTemperature()).isEqualTo(39.8);
        assertThat(savedAlert1.getDeduplicationKey()).isEqualTo("CRITICAL:" + patientA.getId() + ":GLV-ICU-001");
        // Measurement timestamp vs creation timestamp
        assertThat(savedAlert1.getTimestamp().toEpochMilli()).isEqualTo(measurementTime1.toEpochMilli());
        assertThat(savedAlert1.getCreatedAt()).isNotNull();

        // 15 seconds later: repeated critical reading for same patient/device
        Instant measurementTime2 = Instant.now();
        SensorReadingEntity critical2 = new SensorReadingEntity();
        critical2.setId("crit-read-2");
        critical2.setPatientId(patientA.getId());
        critical2.setDeviceId("GLV-ICU-001");
        critical2.setTimestamp(measurementTime2);
        critical2.setHeartRate(158.0);
        critical2.setGsr(8.7);
        critical2.setTemperature(39.9);
        critical2.setSource("SIMULATED");
        critical2.setReceivedAt(measurementTime2);
        critical2.setState("PENDING");
        readings.saveAndFlush(critical2);

        MlDtos.PredictionResponse pred2 = new MlDtos.PredictionResponse(
                patientA.getId(), measurementTime2, "POTENTIAL_ANOMALY", 9.7, "synthetic-scenario-v1",
                List.of(new MlDtos.Contributor("heart_rate", "ABOVE_BASELINE", 158.0, 75.0, 83.0, 9.7)),
                new MlDtos.DataQuality(true, List.of(), List.of(), 4), true,
                "CRITICAL", "High heart rate pattern continues", 0.96, "synthetic-scenario-v1");

        // Duplicate suppression: existing alert is updated, NO duplicate alert created!
        var alert2 = predictionProcessing.storePrediction(critical2, pred2, admin);
        assertThat(alert2).isNotNull();
        assertThat(alert2.id()).isEqualTo(savedAlert1.getId());
        assertThat(alerts.findAll()).hasSize(1); // STILL only 1 alert in database!
        AlertEntity updatedAlert = alerts.findById(savedAlert1.getId()).orElseThrow();
        assertThat(updatedAlert.getHeartRate()).isEqualTo(158.0);
        assertThat(updatedAlert.getTimestamp().toEpochMilli()).isEqualTo(measurementTime2.toEpochMilli());

        // Resolve the incident
        updatedAlert.setStatus("RESOLVED");
        updatedAlert.setResolvedAt(Instant.now());
        alerts.save(updatedAlert);

        // A new incident arrives after prior incident is resolved
        Instant measurementTime3 = Instant.now().plusSeconds(60);
        SensorReadingEntity critical3 = new SensorReadingEntity();
        critical3.setId("crit-read-3");
        critical3.setPatientId(patientA.getId());
        critical3.setDeviceId("GLV-ICU-001");
        critical3.setTimestamp(measurementTime3);
        critical3.setHeartRate(152.0);
        critical3.setGsr(8.1);
        critical3.setTemperature(39.6);
        critical3.setSource("SIMULATED");
        critical3.setReceivedAt(measurementTime3);
        critical3.setState("PENDING");
        readings.saveAndFlush(critical3);

        MlDtos.PredictionResponse pred3 = new MlDtos.PredictionResponse(
                patientA.getId(), measurementTime3, "POTENTIAL_ANOMALY", 9.1, "synthetic-scenario-v1",
                List.of(new MlDtos.Contributor("heart_rate", "ABOVE_BASELINE", 152.0, 75.0, 77.0, 9.1)),
                new MlDtos.DataQuality(true, List.of(), List.of(), 4), true,
                "CRITICAL", "New critical tachycardia incident after resolution", 0.92, "synthetic-scenario-v1");

        var alert3 = predictionProcessing.storePrediction(critical3, pred3, admin);
        assertThat(alert3).isNotNull();
        assertThat(alert3.id()).isNotEqualTo(savedAlert1.getId()); // NEW alert created!
        assertThat(alerts.findAll()).hasSize(2);
    }

    @Test
    void concurrentRequestsDoNotCreateDuplicateActiveIncidents() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<AlertDtos.AlertResponse>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                latch.await();
                Instant t = Instant.now().plusMillis(index);
                SensorReadingEntity r = new SensorReadingEntity();
                r.setId("concurrent-read-" + index);
                r.setPatientId(patientB.getId());
                r.setDeviceId("GLV-ICU-002");
                r.setTimestamp(t);
                r.setHeartRate(160.0);
                r.setGsr(9.0);
                r.setTemperature(40.0);
                r.setSource("SIMULATED");
                r.setReceivedAt(t);
                r.setState("PENDING");
                readings.saveAndFlush(r);

                MlDtos.PredictionResponse pred = new MlDtos.PredictionResponse(
                        patientB.getId(), t, "POTENTIAL_ANOMALY", 9.9, "synthetic-scenario-v1",
                        List.of(new MlDtos.Contributor("heart_rate", "ABOVE_BASELINE", 160.0, 75.0, 85.0, 9.9)),
                        new MlDtos.DataQuality(true, List.of(), List.of(), 4), true,
                        "CRITICAL", "Concurrent critical test", 0.98, "synthetic-scenario-v1");

                return predictionProcessing.storePrediction(r, pred, admin);
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Exactly 1 active alert must exist for patient B
        long openAlertCount = alerts.findAll().stream()
                .filter(a -> patientB.getId().equals(a.getPatientId()) && "OPEN".equals(a.getStatus()))
                .count();
        assertThat(openAlertCount).isEqualTo(1);
    }

    @Test
    void monitoringStatusReflectsGenuineFirebaseReadingAndStaleThreshold() {
        // Given an older reading (> 45s stale threshold)
        Instant oldTime = Instant.now().minusSeconds(120);
        SensorReadingEntity staleReading = new SensorReadingEntity();
        staleReading.setId("firebase-reading-stale");
        staleReading.setPatientId(patientA.getId());
        staleReading.setDeviceId("GLV-ICU-001");
        staleReading.setTimestamp(oldTime);
        staleReading.setHeartRate(48.0);
        staleReading.setTemperature(35.8125);
        staleReading.setGsr(0.47887);
        staleReading.setSource("FIREBASE_RTDB_LIVE");
        staleReading.setReceivedAt(oldTime);
        staleReading.setState("PROCESSED");
        readings.saveAndFlush(staleReading);
        monitoringService.bufferReading(staleReading);

        var staleStatus = monitoringService.evaluatePatient(patientA);
        assertThat(staleStatus).isNotNull();
        assertThat(staleStatus.monitoringStatus()).isEqualTo("STALE");
        assertThat(staleStatus.latestReading()).isNotNull();
        assertThat(staleStatus.latestReading().temperature()).isEqualTo(35.8125);
        assertThat(staleStatus.latestReading().gsr()).isEqualTo(0.47887);
        assertThat(staleStatus.latestReading().source()).isEqualTo("FIREBASE_RTDB_LIVE");

        // Now save a recent reading (< 45s)
        Instant liveTime = Instant.now().minusSeconds(5);
        SensorReadingEntity liveReading = new SensorReadingEntity();
        liveReading.setId("firebase-reading-live");
        liveReading.setPatientId(patientA.getId());
        liveReading.setDeviceId("GLV-ICU-001");
        liveReading.setTimestamp(liveTime);
        liveReading.setHeartRate(78.0);
        liveReading.setTemperature(36.6);
        liveReading.setGsr(1.2345);
        liveReading.setSource("FIREBASE_RTDB_LIVE");
        liveReading.setReceivedAt(liveTime);
        liveReading.setState("PROCESSED");
        readings.saveAndFlush(liveReading);
        monitoringService.bufferReading(liveReading);

        var liveStatus = monitoringService.evaluatePatient(patientA);
        assertThat(liveStatus).isNotNull();
        assertThat(liveStatus.monitoringStatus()).isIn("ACTIVE", "INSUFFICIENT_DATA");
        assertThat(liveStatus.latestReading().heartRate()).isEqualTo(78.0);
        assertThat(liveStatus.latestReading().temperature()).isEqualTo(36.6);
        assertThat(liveStatus.latestReading().source()).isEqualTo("FIREBASE_RTDB_LIVE");
    }
}

