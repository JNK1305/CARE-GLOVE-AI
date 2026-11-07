package ai.careglove;

import ai.careglove.alerts.AlertRepository;
import ai.careglove.audit.AuditLogRepository;
import ai.careglove.common.Role;
import ai.careglove.device.BedEntity;
import ai.careglove.device.BedRepository;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.device.WardEntity;
import ai.careglove.device.WardRepository;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.ml.MlDtos;
import ai.careglove.ml.MlPredictionProcessingService;
import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientRepository;
import ai.careglove.patient.PatientService;
import ai.careglove.ml.MlPredictionRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import ai.careglove.vitals.SensorReadingEntity;
import ai.careglove.vitals.SensorReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:careglove_test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,CONDITION,READ,TRIGGER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "careglove.jwt.secret=care-glove-test-secret-at-least-32-bytes-long",
        "careglove.ml.persistence-windows=1",
        "careglove.ml.recovery-windows=1"
})
@AutoConfigureMockMvc
class BackendFoundationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired DeviceRepository devices;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired SensorReadingRepository readings;
    @Autowired MlPredictionRepository predictions;
    @Autowired AlertRepository alerts;
    @Autowired PatientJourneyRepository journey;
    @Autowired AuditLogRepository audits;
    @Autowired PasswordEncoder passwords;
    @Autowired ai.careglove.auth.JwtService jwt;
    @Autowired PatientAccessService patientAccess;
    @Autowired PatientService patientService;
    @Autowired MlPredictionProcessingService predictionProcessing;

    @BeforeEach
    void prepareDomainRecords() {
        users.deleteAll();
        wards.deleteAll();
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
        DeviceEntity device = new DeviceEntity();
        device.setId("GLV-ICU-001");
        device.setWard("ICU");
        device.setBed("B-01");
        devices.save(device);
        saveUser("senior-a", "doctor-a@example.test", Role.senior);
        saveUser("senior-b", "doctor-b@example.test", Role.senior);
        saveUser("admin-a", "admin@example.test", Role.admin);
    }

    @Test
    void apiAuthPatientAccessAdmissionReadingPredictionAlertAndAudit() throws Exception {
        mvc.perform(get("/api/patients"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patients").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patients").header("Authorization", bearer(users.findById("senior-a").orElseThrow())))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.test","password":"long-test-password","role":"admin"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("admin"));

        var created = patientService.create(new ai.careglove.patient.PatientDtos.CreatePatientRequest(
                "Synthetic Test Patient", "42 yrs", "ICU", "B-01", "GLV-ICU-001", "senior-a",
                "Synthetic integration fixture", "Other", null, null, null, "2026-10-03", "10:30", true),
                users.findById("admin-a").orElseThrow());
        assertThat(created.id()).startsWith("CG-");
        mvc.perform(get("/api/patients/" + created.id())
                        .header("Authorization", bearer(users.findById("senior-a").orElseThrow())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.spo2").doesNotExist());
        assertThat(patientAccess.getVisible(created.id(), users.findById("senior-a").orElseThrow()).getDoctor().getId())
                .isEqualTo("senior-a");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> patientAccess.getVisible(created.id(), users.findById("senior-b").orElseThrow()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/api/patients/" + created.id())
                        .header("Authorization", bearer(users.findById("senior-b").orElseThrow())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/patients/not-a-patient")
                        .header("Authorization", bearer(users.findById("senior-a").orElseThrow())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/vitals/ingest")
                        .header("Authorization", bearer(users.findById("admin-a").orElseThrow()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"","deviceId":"GLV-ICU-001","timestamp":"not-a-timestamp"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/vitals/ingest")
                        .header("Authorization", bearer(users.findById("admin-a").orElseThrow()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","deviceId":"GLV-ICU-001","timestamp":"2026-10-03T10:30:00Z","heartRate":78.0,"temperature":36.7}
                                """.formatted(created.id())))
                .andExpect(status().isBadRequest());

        SensorReadingEntity reading = new SensorReadingEntity();
        reading.setId("reading-test-1");
        reading.setPatientId(created.id());
        reading.setDeviceId("GLV-ICU-001");
        reading.setTimestamp(Instant.parse("2026-10-03T10:30:00Z"));
        reading.setHeartRate(78.0);
        reading.setGsr(0.42);
        reading.setTemperature(36.7);
        reading.setSource("SYNTHETIC");
        reading.setReceivedAt(Instant.now());
        reading.setState("PENDING");
        readings.saveAndFlush(reading);
        MlDtos.PredictionResponse result = new MlDtos.PredictionResponse(
                created.id(), reading.getTimestamp(), "POTENTIAL_ANOMALY", 7.2, "test-mvp",
                List.of(new MlDtos.Contributor("heart_rate", "ABOVE_BASELINE", 78.0, 70.0, 8.0, 7.2)),
                new MlDtos.DataQuality(true, List.of(), List.of(), 10), true);
        var generatedAlert = predictionProcessing.storePrediction(
                reading, result, users.findById("senior-a").orElseThrow());
        assertThat(generatedAlert).isNotNull();
        assertThat(alerts.findAll()).hasSize(1);
        assertThat(alerts.findAll().get(0).getStatus()).isEqualTo("OPEN");
        assertThat(readings.findById("reading-test-1").orElseThrow().getState()).isEqualTo("POTENTIAL_ANOMALY");
        assertThat(predictions.findByReadingId("reading-test-1")).isPresent();
        assertThat(predictions.findByReadingId("reading-test-1").orElseThrow().isScoredWindow()).isTrue();
        assertThat(predictions.findByReadingId("reading-test-1").orElseThrow().getDataQuality())
                .contains("window_readings").contains("10");
        String seniorToken = bearer(users.findById("senior-a").orElseThrow());
        mvc.perform(get("/api/patients/" + created.id() + "/vitals").header("Authorization", seniorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].patientId").value(created.id()))
                .andExpect(jsonPath("$[0].spo2").doesNotExist());
        mvc.perform(get("/api/patients/" + created.id() + "/alerts").header("Authorization", seniorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].patientId").value(created.id()));
        mvc.perform(post("/api/patients/" + created.id() + "/reports").header("Authorization", seniorToken))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.patientId").value(created.id()));
        String alertId = alerts.findAll().get(0).getId();
        mvc.perform(patch("/api/alerts/" + alertId)
                        .header("Authorization", bearer(users.findById("senior-b").orElseThrow()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/alerts/" + alertId)
                        .header("Authorization", seniorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        assertThat(alerts.findById(alertId).orElseThrow().getAcknowledgedAt()).isNotNull();
        mvc.perform(patch("/api/alerts/" + alertId)
                        .header("Authorization", seniorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(alerts.findById(alertId).orElseThrow().getResolvedAt()).isNotNull();
        assertThat(journey.findByPatientIdOrderByTimestampAsc(created.id())).extracting("type")
                .contains("ADMISSION", "ALERT");
        assertThat(audits.findAll()).extracting("action")
                .contains("LOGIN", "PATIENT_CREATE", "PATIENT_VIEW", "ML_PREDICTION", "ALERT_CREATED",
                        "ALERT_ACKNOWLEDGED", "ALERT_RESOLVED", "REPORT_GENERATED");
    }

    @Test
    void mlResponseContractAcceptsFastApiDataQualityAndBackendOwnedPersistence() throws Exception {
        MlDtos.PredictionResponse response = mapper.readerFor(MlDtos.PredictionResponse.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue("""
                        {
                          "patient_id": "patient-contract",
                          "timestamp": "2026-10-03T10:30:00Z",
                          "state": "MONITOR",
                          "anomaly_score": null,
                          "model_version": "mvp-1",
                          "contributors": [],
                          "data_quality": {
                            "valid": false,
                            "missing_signals": ["heart_rate"],
                            "reasons": ["insufficient_personal_baseline"],
                            "window_readings": 1
                          },
                          "persistence": {"active": false, "alert_eligible": false},
                          "scored_window": false
                        }
                        """);
        assertThat(response.patientId()).isEqualTo("patient-contract");
        assertThat(response.dataQuality().missingSignals()).containsExactly("heart_rate");
        assertThat(response.dataQuality().reasons()).containsExactly("insufficient_personal_baseline");
        assertThat(response.dataQuality().windowReadings()).isEqualTo(1);
        assertThat(response.windowScored()).isFalse();
    }

    private void saveUser(String id, String email, Role role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setName(id);
        user.setRole(role);
        user.setPasswordHash(passwords.encode("long-test-password"));
        users.save(user);
    }

    private String bearer(UserEntity user) {
        return "Bearer " + jwt.issue(user);
    }
}
