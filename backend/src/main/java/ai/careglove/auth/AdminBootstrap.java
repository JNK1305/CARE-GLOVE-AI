package ai.careglove.auth;

import ai.careglove.common.Role;
import ai.careglove.device.BedEntity;
import ai.careglove.device.BedRepository;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import ai.careglove.vitals.SensorReadingEntity;
import ai.careglove.vitals.SensorReadingRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final PatientRepository patients;
    private final DeviceRepository devices;
    private final BedRepository beds;
    private final SensorReadingRepository readings;

    @Value("${careglove.app-env:local}")
    private String appEnv;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public AdminBootstrap(
            UserRepository users,
            PasswordEncoder passwords,
            PatientRepository patients,
            DeviceRepository devices,
            BedRepository beds,
            SensorReadingRepository readings) {
        this.users = users;
        this.passwords = passwords;
        this.patients = patients;
        this.devices = devices;
        this.beds = beds;
        this.readings = readings;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapConfiguredAdmin();
        if ("local".equalsIgnoreCase(appEnv) && (datasourceUrl == null || !datasourceUrl.contains("h2:mem"))) {
            bootstrapLocalDevData();
        }
    }

    private void bootstrapConfiguredAdmin() {
        String email = System.getenv("ADMIN_BOOTSTRAP_EMAIL");
        String password = System.getenv("ADMIN_BOOTSTRAP_PASSWORD");
        if ((email == null || email.isBlank()) && (password == null || password.isBlank())) return;
        if (email == null || email.isBlank() || password == null || password.length() < 16) {
            throw new IllegalStateException("Set both ADMIN_BOOTSTRAP_EMAIL and a 16-character ADMIN_BOOTSTRAP_PASSWORD, or leave both unset.");
        }
        if (users.findByEmailIgnoreCase(email).isPresent()) return;
        UserEntity admin = new UserEntity();
        admin.setId("admin-" + java.util.UUID.randomUUID());
        admin.setEmail(email.trim().toLowerCase());
        admin.setName("CARE-GLOVE Administrator");
        admin.setRole(Role.admin);
        admin.setPasswordHash(passwords.encode(password));
        users.save(admin);
    }

    private void bootstrapLocalDevData() {
        UserEntity admin = users.findByEmailIgnoreCase("admin@careglove.demo").orElseGet(() -> {
            UserEntity u = new UserEntity();
            u.setId("admin-demo");
            u.setEmail("admin@careglove.demo");
            u.setName("Care Glove Admin");
            u.setRole(Role.admin);
            u.setPasswordHash(passwords.encode("demo"));
            return users.save(u);
        });

        UserEntity doctor = users.findByEmailIgnoreCase("doctor@careglove.demo").orElseGet(() -> {
            UserEntity u = new UserEntity();
            u.setId("sharma");
            u.setEmail("doctor@careglove.demo");
            u.setName("Dr. Amit Sharma");
            u.setRole(Role.senior);
            u.setPasswordHash(passwords.encode("demo"));
            return users.save(u);
        });

        UserEntity doctorPatil = users.findByEmailIgnoreCase("doctor2@careglove.demo").orElseGet(() -> {
            UserEntity u = new UserEntity();
            u.setId("patil");
            u.setEmail("doctor2@careglove.demo");
            u.setName("Dr. Priya Patil");
            u.setRole(Role.senior);
            u.setPasswordHash(passwords.encode("demo"));
            return users.save(u);
        });

        users.findByEmailIgnoreCase("staff@careglove.demo").orElseGet(() -> {
            UserEntity u = new UserEntity();
            u.setId("duty-demo");
            u.setEmail("staff@careglove.demo");
            u.setName("Duty Staff");
            u.setRole(Role.duty);
            u.setPasswordHash(passwords.encode("demo"));
            return users.save(u);
        });

        seedPatientIfMissing("CG-1042", "Rahul Patil", "54 yrs", "ICU", "B-02", "ICU:B-02", "GLV-ICU-002", doctor, "Critical", 96.0, 37.4, 5.0, true);
        seedPatientIfMissing("CG-1044", "Imran Shaikh", "61 yrs", "ICU", "B-03", "ICU:B-03", "GLV-ICU-003", doctorPatil, "Stable", 78.0, 36.8, 4.2, true);
        seedPatientIfMissing("CG-1045", "Baby of Priya N.", "2 days", "NICU", "B-01", "NICU:B-01", "GLV-NICU-001", doctorPatil, "Stable", 122.0, 36.7, 2.2, true);
        seedPatientIfMissing("CG-1046", "Kavita More", "38 yrs", "Special Ward", "B-01", "Special Ward:B-01", "GLV-SPL-001", doctor, "Stable", 72.0, 36.6, 3.6, true);
    }

    private void seedPatientIfMissing(
            String id, String name, String age, String ward, String bed, String bedId,
            String glove, UserEntity doc, String status, Double bpm, Double temp, Double gsr, boolean authorized) {
        if (patients.findById(id).isPresent()) return;
        PatientEntity p = new PatientEntity();
        p.setId(id);
        p.setName(name);
        p.setAge(age);
        p.setWard(ward);
        p.setBed(bed);
        p.setBedId(bedId);
        p.setGlove(glove);
        p.setDoctor(doc);
        p.setStatus(status);
        p.setBpm(bpm);
        p.setTemp(temp);
        p.setGsr(gsr);
        p.setAuthorized(authorized);
        p.setLifecycle("ADMITTED");
        p.setMonitoringStatus("ACTIVE");
        p.setCreatedAt(Instant.now());
        patients.save(p);

        beds.findById(bedId).ifPresent(b -> {
            b.setGloveId(glove);
            beds.save(b);
        });
        devices.findById(glove).ifPresent(d -> {
            d.setPatientId(id);
            d.setStatus("ASSIGNED");
            devices.save(d);
        });

        SensorReadingEntity r = new SensorReadingEntity();
        r.setId("INIT-" + id);
        r.setPatientId(id);
        r.setDeviceId(glove);
        r.setTimestamp(Instant.now());
        r.setHeartRate(bpm);
        r.setTemperature(temp);
        r.setGsr(gsr);
        r.setSource("DEMO_SENSOR");
        r.setState("PENDING");
        r.setReceivedAt(Instant.now());
        readings.save(r);
    }
}
