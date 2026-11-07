package ai.careglove;

import ai.careglove.common.Role;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.firebase.FirebaseIngestionService;
import ai.careglove.firebase.FirebaseProperties;
import ai.careglove.firebase.FirebaseSensorReading;
import ai.careglove.ml.MlDtos;
import ai.careglove.ml.MlServiceClient;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import ai.careglove.vitals.SensorReadingEntity;
import ai.careglove.vitals.SensorReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:careglove_fb_test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,CONDITION,READ,TRIGGER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "careglove.jwt.secret=care-glove-test-secret-at-least-32-bytes-long",
        "careglove.firebase.enabled=false",
        "careglove.firebase.database-url=https://mock-firebase.test/",
        "careglove.firebase.default-device-id=GLV-FB-001",
        "careglove.firebase.default-patient-id=CG-FB-001"
})
class FirebaseIngestionTest {

    @Autowired FirebaseIngestionService firebaseIngestionService;
    @Autowired FirebaseProperties properties;
    @Autowired SensorReadingRepository readings;
    @Autowired PatientRepository patients;
    @Autowired DeviceRepository devices;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper objectMapper;

    @MockBean MlServiceClient mlServiceClient;

    private PatientEntity testPatient;
    private DeviceEntity testDevice;
    private UserEntity testDoctor;

    @BeforeEach
    void setUp() {
        readings.deleteAll();
        devices.deleteAll();
        patients.deleteAll();
        users.deleteAll();

        testDoctor = new UserEntity();
        testDoctor.setId("doc-1");
        testDoctor.setName("Dr. Test");
        testDoctor.setEmail("doc@test.com");
        testDoctor.setRole(Role.senior);
        testDoctor.setPasswordHash(passwords.encode("pass"));
        testDoctor = users.save(testDoctor);

        UserEntity admin = new UserEntity();
        admin.setId("admin-1");
        admin.setName("Admin User");
        admin.setEmail("admin@careglove.demo");
        admin.setRole(Role.admin);
        admin.setPasswordHash(passwords.encode("demo"));
        users.save(admin);

        testPatient = new PatientEntity();
        testPatient.setId("CG-FB-001");
        testPatient.setName("Firebase Test Patient");
        testPatient.setAge("42 yrs");
        testPatient.setWard("ICU");
        testPatient.setBed("B-01");
        testPatient.setBedId("ICU:B-01");
        testPatient.setGlove("GLV-FB-001");
        testPatient.setDoctor(testDoctor);
        testPatient.setStatus("Stable");
        testPatient.setAuthorized(true);
        testPatient.setLifecycle("ADMITTED");
        testPatient.setMonitoringStatus("ACTIVE");
        testPatient = patients.save(testPatient);

        testDevice = new DeviceEntity();
        testDevice.setId("GLV-FB-001");
        testDevice.setPatientId("CG-FB-001");
        testDevice.setWard("ICU");
        testDevice.setBed("B-01");
        testDevice.setStatus("ASSIGNED");
        testDevice = devices.save(testDevice);

        when(mlServiceClient.predict(any())).thenAnswer(inv -> {
            ai.careglove.vitals.NormalizedSensorReading r = inv.getArgument(0);
            return new MlDtos.PredictionResponse(
                    r.patientId(),
                    r.timestamp(),
                    "NORMAL",
                    0.12,
                    "NORMAL",
                    java.util.List.of(),
                    new MlDtos.DataQuality(true, java.util.List.of(), java.util.List.of(), 5),
                    true,
                    "NORMAL",
                    "Normal physiological rhythm",
                    0.95,
                    "classifier-1.0");
        });
    }

    @Test
    void testPhysiologicalValidationBounds() {
        // 1. Normal reading passes
        FirebaseSensorReading valid = new FirebaseSensorReading();
        valid.setBpm(72.0);
        valid.setTemperature(36.6);
        valid.setGsr(1.5);
        valid.setTimestamp(System.currentTimeMillis() - 1000);
        assertThat(valid.validate().isValid()).isTrue();

        // 2. BPM = 0 (sensor off) fails
        FirebaseSensorReading zeroBpm = new FirebaseSensorReading();
        zeroBpm.setBpm(0.0);
        zeroBpm.setTemperature(36.6);
        zeroBpm.setGsr(1.5);
        assertThat(zeroBpm.validate().isValid()).isFalse();
        assertThat(zeroBpm.validate().reason()).contains("BPM 0.0 outside physiological bounds");

        // 3. Tachycardia beyond human limit fails
        FirebaseSensorReading extremeBpm = new FirebaseSensorReading();
        extremeBpm.setBpm(290.0);
        extremeBpm.setTemperature(36.6);
        extremeBpm.setGsr(1.5);
        assertThat(extremeBpm.validate().isValid()).isFalse();

        // 4. Hypothermic / sensor disconnected temperature fails
        FirebaseSensorReading coldTemp = new FirebaseSensorReading();
        coldTemp.setBpm(70.0);
        coldTemp.setTemperature(15.0);
        coldTemp.setGsr(1.5);
        assertThat(coldTemp.validate().isValid()).isFalse();
        assertThat(coldTemp.validate().reason()).contains("outside physiological bounds");

        // 5. Extreme fever beyond physiological bounds fails
        FirebaseSensorReading extremeTemp = new FirebaseSensorReading();
        extremeTemp.setBpm(70.0);
        extremeTemp.setTemperature(48.0);
        extremeTemp.setGsr(1.5);
        assertThat(extremeTemp.validate().isValid()).isFalse();

        // 6. Negative GSR fails
        FirebaseSensorReading negGsr = new FirebaseSensorReading();
        negGsr.setBpm(70.0);
        negGsr.setTemperature(36.6);
        negGsr.setGsr(-2.0);
        assertThat(negGsr.validate().isValid()).isFalse();

        // 7. Missing vital fails
        FirebaseSensorReading missingBpm = new FirebaseSensorReading();
        missingBpm.setTemperature(36.6);
        missingBpm.setGsr(1.5);
        assertThat(missingBpm.validate().isValid()).isFalse();
        assertThat(missingBpm.validate().reason()).contains("Heart rate (BPM) is missing");
    }

    @Test
    void testFirebaseFieldResolution() throws Exception {
        // Tests the actual real Firebase RTDB json format from /sensorData:
        // {"bpm":37,"gsr_conductance":0,"gsr_raw":4095,"ir":8178,"led_level":255,"max30105_temp":37.6875,"stress_level":"Very Low","timestamp":635866}
        String sensorDataJson = """
        {
          "bpm": 76,
          "gsr_conductance": 2.1,
          "gsr_raw": 1200,
          "ir": 8178,
          "led_level": 255,
          "max30105_temp": 37.125,
          "stress_level": "Very Low",
          "timestamp": 635866
        }
        """;

        FirebaseSensorReading reading = objectMapper.readValue(sensorDataJson, FirebaseSensorReading.class);
        assertThat(reading.resolveBpm()).isEqualTo(76.0);
        assertThat(reading.resolveTemperature()).isEqualTo(37.125);
        assertThat(reading.resolveGsr()).isEqualTo(2.1);
        assertThat(reading.getIr()).isEqualTo(8178.0);
        assertThat(reading.validate().isValid()).isTrue();

        // Tests the actual real Firebase RTDB json format from /sensorHistory:
        // {"bpm":73,"gsr":0.48423,"spo2":89,"stress":"Very Low","temperature":33.125,"timestamp":1784800214943}
        String sensorHistoryJson = """
        {
          "bpm": 73,
          "gsr": 0.48423,
          "spo2": 89,
          "stress": "Very Low",
          "temperature": 33.125,
          "timestamp": 1784800214943
        }
        """;
        FirebaseSensorReading historyReading = objectMapper.readValue(sensorHistoryJson, FirebaseSensorReading.class);
        assertThat(historyReading.resolveBpm()).isEqualTo(73.0);
        assertThat(historyReading.resolveTemperature()).isEqualTo(33.125);
        assertThat(historyReading.resolveGsr()).isEqualTo(0.48423);
        assertThat(historyReading.getSpo2()).isEqualTo(89.0);
        assertThat(historyReading.resolveTimestamp(Instant.now()).toEpochMilli()).isEqualTo(1784800214943L);
        assertThat(historyReading.validate().isValid()).isTrue();
    }

    @Test
    void testDisconnectionHandlingDoesNotFabricateData() {
        // Poll against mock-firebase.test (which will fail to connect)
        FirebaseIngestionService.IngestionResult result = firebaseIngestionService.pollOnce();

        assertThat(result.success()).isFalse();
        assertThat(result.readingsIngested()).isEqualTo(0);
        assertThat(result.error()).isNotNull();

        // Status should indicate disconnected and capture error
        FirebaseIngestionService.FirebaseStatus status = firebaseIngestionService.getStatus();
        assertThat(status.connected()).isFalse();
        assertThat(status.deviceState()).isEqualTo("DISCONNECTED");
        assertThat(status.lastError()).contains("Firebase");

        // Verify NO fake readings were manufactured in database
        List<SensorReadingEntity> saved = readings.findByPatientIdOrderByTimestampDesc("CG-FB-001");
        assertThat(saved).isEmpty();
    }

    @Test
    void testInadequateSensorContactDetection() throws Exception {
        // Raw hardware payload sampled from disconnected sensor:
        // {"bpm":37,"gsr_conductance":0,"gsr_raw":4095,"ir":8178,"led_level":255,"max30105_temp":37.6875,"stress_level":"Very Low","timestamp":635866}
        String disconnectedPayload = """
        {
          "bpm": 37,
          "gsr_conductance": 0,
          "gsr_raw": 4095,
          "ir": 8178,
          "led_level": 255,
          "max30105_temp": 37.6875,
          "stress_level": "Very Low",
          "timestamp": 635866
        }
        """;
        FirebaseSensorReading reading = objectMapper.readValue(disconnectedPayload, FirebaseSensorReading.class);

        // Verify contact detection flags open-circuit GSR and optical noise
        assertThat(reading.isSensorContactAdequate()).isFalse();
        assertThat(reading.calculateSignalQuality()).isEqualTo(0.05);

        // Good contact payload
        String goodContactPayload = """
        {
          "bpm": 74,
          "gsr_conductance": 2.5,
          "gsr_raw": 1500,
          "ir": 95000,
          "led_level": 60,
          "max30105_temp": 36.8,
          "timestamp": 636000
        }
        """;
        FirebaseSensorReading goodReading = objectMapper.readValue(goodContactPayload, FirebaseSensorReading.class);
        assertThat(goodReading.isSensorContactAdequate()).isTrue();
        assertThat(goodReading.calculateSignalQuality()).isEqualTo(0.95);
    }

    @Test
    void testDeviceConnectionStatesAndValidationHandling() throws Exception {
        // Validation check on out-of-range sensor values (e.g. BPM = 0 or temperature = 60)
        String invalidPayload = """
        {
          "bpm": 0,
          "gsr": 0.0,
          "temperature": 55.0,
          "timestamp": 635866
        }
        """;
        FirebaseSensorReading invalidReading = objectMapper.readValue(invalidPayload, FirebaseSensorReading.class);
        FirebaseSensorReading.ValidationResult result = invalidReading.validate();
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).contains("BPM 0.0 outside physiological bounds");

        // Freshness configuration verification
        assertThat(properties.getFreshnessWindowSeconds()).isEqualTo(45);
        properties.setFreshnessWindowSeconds(60);
        assertThat(properties.getFreshnessWindowSeconds()).isEqualTo(60);
        properties.setFreshnessWindowSeconds(45); // restore
    }
}
