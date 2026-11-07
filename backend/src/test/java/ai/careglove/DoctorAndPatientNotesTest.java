package ai.careglove;

import ai.careglove.audit.AuditLogRepository;
import ai.careglove.clinical.ClinicalRecordDtos;
import ai.careglove.clinical.ClinicalRecordRepository;
import ai.careglove.common.Role;
import ai.careglove.device.BedEntity;
import ai.careglove.device.BedRepository;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.device.WardEntity;
import ai.careglove.device.WardRepository;
import ai.careglove.doctor.DoctorDtos;
import ai.careglove.patient.PatientDtos;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.patient.PatientService;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:careglove_doc_test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,CONDITION,READ,TRIGGER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "careglove.jwt.secret=care-glove-test-secret-at-least-32-bytes-long",
        "careglove.firebase.enabled=false"
})
@AutoConfigureMockMvc
class DoctorAndPatientNotesTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired ClinicalRecordRepository clinicalRecords;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired DeviceRepository devices;
    @Autowired PatientService patientService;
    @Autowired ai.careglove.patient.AdmissionRepository admissions;
    @Autowired ai.careglove.journey.PatientJourneyRepository journey;
    @Autowired ai.careglove.alerts.AlertRepository alerts;
    @Autowired PasswordEncoder passwords;
    @Autowired ai.careglove.auth.JwtService jwt;
    @Autowired AuditLogRepository audits;

    private UserEntity careAdmin;
    private UserEntity metroAdmin;
    private UserEntity careDoctor;
    private UserEntity otherDoctor;
    private UserEntity dutyStaff;
    private PatientEntity testPatient;

    @BeforeEach
    void setUp() {
        clinicalRecords.deleteAll();
        alerts.deleteAll();
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

        DeviceEntity device = new DeviceEntity();
        device.setId("GLV-ICU-001");
        device.setWard("ICU");
        device.setBed("B-01");
        device.setStatus("AVAILABLE");
        device.setUpdatedAt(Instant.now());
        devices.save(device);

        careAdmin = createUser("admin-care", "admin@care.hospital", "Admin Care", Role.admin, "CARE Hospital");
        metroAdmin = createUser("admin-metro", "admin@metro.hospital", "Admin Metro", Role.admin, "Metro Hospital");
        careDoctor = createUser("doc-sharma", "sharma@care.hospital", "Dr. Amit Sharma", Role.senior, "CARE Hospital");
        otherDoctor = createUser("doc-patil", "patil@care.hospital", "Dr. Priya Patil", Role.senior, "CARE Hospital");
        dutyStaff = createUser("duty-anita", "anita@care.hospital", "Anita Joshi", Role.duty, "CARE Hospital");

        var created = patientService.create(new PatientDtos.CreatePatientRequest(
                "John Doe", "55 yrs", "ICU", "B-01", "GLV-ICU-001", careDoctor.getId(),
                "Post-op observation", "Male", null, null, null, "2026-10-21", "08:00", true, "CARE Hospital"),
                careAdmin);
        testPatient = patients.findById(created.id()).orElseThrow();
    }

    private UserEntity createUser(String id, String email, String name, Role role, String hospital) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(email);
        u.setName(name);
        u.setRole(role);
        u.setHospital(hospital);
        u.setPasswordHash(passwords.encode("Password12345!"));
        u.setActive(true);
        return users.save(u);
    }

    private String bearer(UserEntity user) {
        return "Bearer " + jwt.issue(user);
    }

    @Test
    void test1_authorizedAdminCreatesDoctorSuccessfully() throws Exception {
        DoctorDtos.CreateDoctorRequest req = new DoctorDtos.CreateDoctorRequest(
                "Dr. Sarah Khan", "sarah.khan@care.hospital", "CARE Hospital",
                "Cardiology & Critical Care", "+91 98765 43210", "DOC-1001", "Password12345!");

        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(careAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dr. Sarah Khan"))
                .andExpect(jsonPath("$.email").value("sarah.khan@care.hospital"))
                .andExpect(jsonPath("$.hospital").value("CARE Hospital"))
                .andExpect(jsonPath("$.specialty").value("Cardiology & Critical Care"))
                .andExpect(jsonPath("$.contact").value("+91 98765 43210"))
                .andExpect(jsonPath("$.employeeId").value("DOC-1001"))
                .andExpect(jsonPath("$.role").value("senior"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        UserEntity saved = users.findByEmailIgnoreCase("sarah.khan@care.hospital").orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.senior);
        assertThat(saved.getHospital()).isEqualTo("CARE Hospital");
        assertThat(saved.getSpecialty()).isEqualTo("Cardiology & Critical Care");
        assertThat(passwords.matches("Password12345!", saved.getPasswordHash())).isTrue();
    }

    @Test
    void test2_unauthorizedUserCannotCreateDoctor() throws Exception {
        DoctorDtos.CreateDoctorRequest req = new DoctorDtos.CreateDoctorRequest(
                "Dr. Intruder", "intruder@care.hospital", "CARE Hospital",
                "Neurology", null, null, "Password12345!");

        // Doctor cannot create doctors
        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // Duty staff cannot create doctors
        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(dutyStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // Unauthenticated request
        mvc.perform(post("/api/doctors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void test3_adminCannotAssignDoctorToUnauthorizedHospital() throws Exception {
        DoctorDtos.CreateDoctorRequest req = new DoctorDtos.CreateDoctorRequest(
                "Dr. Cross Hospital", "cross@metro.hospital", "Metro Hospital",
                "Surgery", null, null, "Password12345!");

        // Admin of CARE Hospital tries to create doctor in Metro Hospital
        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(careAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void test4_duplicateDoctorAccountsAndAssignmentsHandledSafely() throws Exception {
        DoctorDtos.CreateDoctorRequest req1 = new DoctorDtos.CreateDoctorRequest(
                "Dr. First", "sharma@care.hospital", "CARE Hospital",
                "Cardiology", null, "DOC-DUPLICATE", "Password12345!");

        // Duplicate email
        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(careAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isConflict());

        // Create first with employeeId DOC-DUPLICATE
        DoctorDtos.CreateDoctorRequest req2 = new DoctorDtos.CreateDoctorRequest(
                "Dr. Second", "second@care.hospital", "CARE Hospital",
                "Cardiology", null, "DOC-DUPLICATE", "Password12345!");
        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(careAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());

        // Attempt same employeeId in same hospital
        DoctorDtos.CreateDoctorRequest req3 = new DoctorDtos.CreateDoctorRequest(
                "Dr. Third", "third@care.hospital", "CARE Hospital",
                "Pediatrics", null, "DOC-DUPLICATE", "Password12345!");
        mvc.perform(post("/api/doctors")
                        .header("Authorization", bearer(careAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req3)))
                .andExpect(status().isConflict());
    }

    @Test
    void test5_seniorDoctorCreatesNoteForCorrectPatient() throws Exception {
        ClinicalRecordDtos.CreateRequest noteReq = new ClinicalRecordDtos.CreateRequest(
                "NOTE", "Patient is responding well to treatment. Keep monitoring vitals every 15 minutes.",
                null, null, null, null, null, null, null);

        mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noteReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.actorId").value(careDoctor.getId()))
                .andExpect(jsonPath("$.actorName").value(careDoctor.getName()))
                .andExpect(jsonPath("$.actorRole").value("senior"))
                .andExpect(jsonPath("$.patientId").value(testPatient.getId()))
                .andExpect(jsonPath("$.hospital").value("CARE Hospital"))
                .andExpect(jsonPath("$.content").value("Patient is responding well to treatment. Keep monitoring vitals every 15 minutes."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void test6_notePersistsInDatabaseAndRemainsAvailableAfterReloading() throws Exception {
        ClinicalRecordDtos.CreateRequest noteReq = new ClinicalRecordDtos.CreateRequest(
                "NOTE", "Initial admission evaluation. Patient is stable.",
                null, null, null, null, null, null, null);

        String responseJson = mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noteReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ClinicalRecordDtos.RecordResponse created = mapper.readValue(responseJson, ClinicalRecordDtos.RecordResponse.class);
        assertThat(created.id()).isNotEmpty();

        // Verify retrieval via GET
        mvc.perform(get("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(created.id()))
                .andExpect(jsonPath("$[0].content").value("Initial admission evaluation. Patient is stable."))
                .andExpect(jsonPath("$[0].updatedAt").doesNotExist());

        // Update note
        ClinicalRecordDtos.UpdateRequest updateReq = new ClinicalRecordDtos.UpdateRequest(
                "Updated evaluation: Patient showed mild tachycardia at 09:00, settled now.");
        mvc.perform(put("/api/patients/" + testPatient.getId() + "/clinical-records/" + created.id())
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.actorId").value(careDoctor.getId()))
                .andExpect(jsonPath("$.content").value("Updated evaluation: Patient showed mild tachycardia at 09:00, settled now."))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // Query again to verify persistence of updated note
        mvc.perform(get("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Updated evaluation: Patient showed mild tachycardia at 09:00, settled now."))
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }

    @Test
    void test7_authorizedStaffCanViewNotesForPatientsTheyArePermittedToAccess() throws Exception {
        ClinicalRecordDtos.CreateRequest noteReq = new ClinicalRecordDtos.CreateRequest(
                "NOTE", "Care team daily round note.",
                null, null, null, null, null, null, null);
        mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noteReq)))
                .andExpect(status().isCreated());

        // Admin of same hospital can view
        mvc.perform(get("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Care team daily round note."));

        // Duty staff of same hospital with patient authorization can view
        mvc.perform(get("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(dutyStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Care team daily round note."));
    }

    @Test
    void test8_unauthorizedUsersAndStaffFromUnrelatedHospitalsCannotAccessNotes() throws Exception {
        // Staff from Metro Hospital cannot access patient or notes of CARE Hospital
        mvc.perform(get("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(metroAdmin)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(metroAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ClinicalRecordDtos.CreateRequest("NOTE", "Illegal note", null, null, null, null, null, null, null))))
                .andExpect(status().isForbidden());

        // Doctor not assigned to this patient cannot access
        mvc.perform(get("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(otherDoctor)))
                .andExpect(status().isForbidden());
    }

    @Test
    void test9_noteAuthorAndTimestampsAreCorrect() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        ClinicalRecordDtos.CreateRequest noteReq = new ClinicalRecordDtos.CreateRequest(
                "NOTE", "Clinical timestamp accuracy verification.",
                null, null, null, null, null, null, null);

        String json = mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noteReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ClinicalRecordDtos.RecordResponse note = mapper.readValue(json, ClinicalRecordDtos.RecordResponse.class);
        assertThat(note.actorId()).isEqualTo(careDoctor.getId());
        assertThat(note.actorName()).isEqualTo(careDoctor.getName());
        assertThat(note.actorRole()).isEqualTo("senior");
        assertThat(note.timestamp()).isAfterOrEqualTo(before);
        assertThat(note.hospital()).isEqualTo("CARE Hospital");
    }

    @Test
    void test10_emptyNotesAndExcessiveLengthAreRejected() throws Exception {
        // Empty note
        mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ClinicalRecordDtos.CreateRequest("NOTE", "   ", null, null, null, null, null, null, null))))
                .andExpect(status().isBadRequest());

        // Note exceeding 5000 chars
        String hugeContent = "A".repeat(5001);
        mvc.perform(post("/api/patients/" + testPatient.getId() + "/clinical-records")
                        .header("Authorization", bearer(careDoctor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ClinicalRecordDtos.CreateRequest("NOTE", hugeContent, null, null, null, null, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void test11_doctorsListReturnsScopedResultsWithoutSecrets() throws Exception {
        mvc.perform(get("/api/doctors")
                        .header("Authorization", bearer(careAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].email").isNotEmpty())
                .andExpect(jsonPath("$[0].hospital").value("CARE Hospital"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].token").doesNotExist());
    }
}
