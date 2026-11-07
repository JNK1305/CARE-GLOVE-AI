package ai.careglove.patient;

import ai.careglove.audit.AuditService;
import ai.careglove.common.Role;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.device.BedRepository;
import ai.careglove.journey.PatientJourneyEntity;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PatientService {
    private static final Map<String, String> WARD_PREFIX = Map.of(
            "ICU", "ICU", "NICU", "NICU", "Special Ward", "SPL", "General Ward", "GEN");
    private final PatientRepository patients;
    private final UserRepository users;
    private final DeviceRepository devices;
    private final BedRepository beds;
    private final AdmissionRepository admissions;
    private final PatientJourneyRepository journey;
    private final PatientAccessService access;
    private final AuditService audit;

    public PatientService(
            PatientRepository patients,
            UserRepository users,
            DeviceRepository devices,
            BedRepository beds,
            AdmissionRepository admissions,
            PatientJourneyRepository journey,
            PatientAccessService access,
            AuditService audit) {
        this.patients = patients;
        this.users = users;
        this.devices = devices;
        this.beds = beds;
        this.admissions = admissions;
        this.journey = journey;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PatientDtos.PatientResponse> list(UserEntity user) {
        return access.visibleTo(user).stream().map(PatientService::response).toList();
    }

    @Transactional
    public PatientDtos.PatientResponse get(String id, UserEntity user) {
        PatientEntity patient = access.getVisible(id, user);
        audit.record(user.getId(), "PATIENT_VIEW", "PATIENT", id, Map.of());
        return response(patient);
    }

    @Transactional
    public PatientDtos.PatientResponse create(PatientDtos.CreatePatientRequest request, UserEntity actor) {
        if (actor.getRole() != Role.admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators can admit patients.");
        validateLocation(request.ward(), request.bed(), request.glove());
        if (patients.findByWardAndBedAndLifecycleNot(request.ward(), request.bed(), "DISCHARGED").isPresent()) {
            throw new IllegalArgumentException("The selected bed is already assigned to an active patient.");
        }
        UserEntity doctor = users.findById(request.doctorId())
                .filter(user -> user.getRole() == Role.senior && user.isActive())
                .orElseThrow(() -> new IllegalArgumentException("Select an active Senior Doctor account."));
        String hospital = request.hospital() != null && !request.hospital().isBlank()
                ? request.hospital().trim()
                : (actor.getHospital() != null ? actor.getHospital() : "CARE Hospital");
        if (actor.getHospital() != null && !actor.getHospital().isBlank()
                && !actor.getHospital().equalsIgnoreCase(hospital)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot admit patient to a different hospital.");
        }
        if (doctor.getHospital() != null && !doctor.getHospital().isBlank()
                && !doctor.getHospital().equalsIgnoreCase(hospital)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot assign a doctor from a different hospital.");
        }
        Instant now = Instant.now();
        PatientEntity patient = new PatientEntity();
        patient.setId("CG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        patient.setName(request.name().trim());
        patient.setAge(request.age().trim());
        patient.setWard(request.ward());
        patient.setBed(request.bed());
        patient.setBedId(request.ward() + ":" + request.bed());
        patient.setGlove(request.glove());
        patient.setDoctor(doctor);
        patient.setHospital(hospital);
        patient.setStatus("Stable");
        patient.setBpm(null);
        patient.setTemp(null);
        patient.setGsr(null);
        patient.setAuthorized(Boolean.TRUE.equals(request.authorized()));
        patient.setGender(request.gender());
        patient.setContact(request.contact());
        patient.setEmergencyContact(request.emergencyContact());
        patient.setBloodGroup(request.bloodGroup());
        patient.setCondition(request.condition().trim());
        patient.setAdmissionDate(request.admissionDate());
        patient.setAdmissionTime(request.admissionTime());
        patient.setLifecycle("ADMITTED");
        patient.setMonitoringStatus("READY");
        patient = patients.save(patient);

        AdmissionEntity admission = new AdmissionEntity();
        admission.setId(UUID.randomUUID().toString());
        admission.setPatient(patient);
        admission.setAdmissionDate(patient.getAdmissionDate());
        admission.setAdmissionTime(patient.getAdmissionTime());
        admission.setCondition(patient.getCondition());
        admission.setLifecycle(patient.getLifecycle());
        admissions.save(admission);

        DeviceEntity device = devices.findById(patient.getGlove()).orElseGet(DeviceEntity::new);
        device.setId(patient.getGlove());
        device.setPatientId(patient.getId());
        device.setWard(patient.getWard());
        device.setBed(patient.getBed());
        device.setStatus("ASSIGNED");
        device.setUpdatedAt(now);
        devices.save(device);
        event(patient, "ADMISSION", actor.getName(), patient.getName() + " admitted", now);
        event(patient, "WARD_ASSIGNMENT", actor.getName(), "Assigned to " + patient.getWard(), now);
        event(patient, "BED_ASSIGNMENT", actor.getName(), "Bed " + patient.getBed() + " assigned", now);
        event(patient, "GLOVE_ASSIGNMENT", actor.getName(), "Glove " + patient.getGlove() + " assigned", now);
        event(patient, "DOCTOR_ASSIGNMENT", actor.getName(), doctor.getName() + " assigned", now);
        audit.record(actor.getId(), "PATIENT_CREATE", "PATIENT", patient.getId(), Map.of("ward", patient.getWard()));
        return response(patient);
    }

    @Transactional
    public PatientDtos.PatientResponse update(String id, PatientDtos.UpdatePatientRequest request, UserEntity actor) {
        PatientEntity patient = patients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found."));
        if (actor.getRole() != Role.admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators can update patient records.");
        String ward = request.ward() == null ? patient.getWard() : request.ward();
        String bed = request.bed() == null ? patient.getBed() : request.bed();
        String glove = request.glove() == null ? patient.getGlove() : request.glove();
        validateLocation(ward, bed, glove);
        if (!ward.equals(patient.getWard()) || !bed.equals(patient.getBed())) {
            String patientId = patient.getId();
            patients.findByWardAndBedAndLifecycleNot(ward, bed, "DISCHARGED")
                    .filter(other -> !other.getId().equals(patientId))
                    .ifPresent(other -> { throw new IllegalArgumentException("The selected bed is already assigned to an active patient."); });
        }
        if (request.doctorId() != null) {
            UserEntity doctor = users.findById(request.doctorId())
                    .filter(user -> user.getRole() == Role.senior && user.isActive())
                    .orElseThrow(() -> new IllegalArgumentException("Select an active Senior Doctor account."));
            patient.setDoctor(doctor);
        }
        if (request.name() != null) patient.setName(request.name().trim());
        if (request.age() != null) patient.setAge(request.age().trim());
        patient.setWard(ward);
        patient.setBed(bed);
        patient.setBedId(ward + ":" + bed);
        patient.setGlove(glove);
        if (request.condition() != null) patient.setCondition(request.condition());
        if (request.gender() != null) patient.setGender(request.gender());
        if (request.contact() != null) patient.setContact(request.contact());
        if (request.emergencyContact() != null) patient.setEmergencyContact(request.emergencyContact());
        if (request.bloodGroup() != null) patient.setBloodGroup(request.bloodGroup());
        if (request.admissionDate() != null) patient.setAdmissionDate(request.admissionDate());
        if (request.admissionTime() != null) patient.setAdmissionTime(request.admissionTime());
        if (request.lifecycle() != null) patient.setLifecycle(validated(request.lifecycle(), List.of("ADMITTED", "ACTIVE", "DISCHARGED"), "lifecycle"));
        if (request.monitoringStatus() != null) patient.setMonitoringStatus(validated(request.monitoringStatus(), List.of("READY", "ACTIVE", "DISCHARGED"), "monitoringStatus"));
        if (request.status() != null) patient.setStatus(validated(request.status(), List.of("Stable", "Watch", "Critical"), "status"));
        if (request.authorized() != null) patient.setAuthorized(request.authorized());
        patient = patients.save(patient);
        DeviceEntity device = devices.findById(patient.getGlove()).orElseGet(DeviceEntity::new);
        device.setId(patient.getGlove());
        device.setPatientId(patient.getId());
        device.setWard(patient.getWard());
        device.setBed(patient.getBed());
        device.setStatus(patient.getLifecycle().equals("DISCHARGED") ? "AVAILABLE" : patient.getMonitoringStatus().equals("ACTIVE") ? "DEMO ACTIVE" : "ASSIGNED");
        device.setUpdatedAt(Instant.now());
        devices.save(device);
        audit.record(actor.getId(), "PATIENT_UPDATE", "PATIENT", patient.getId(), Map.of());
        return response(patient);
    }

    private void validateLocation(String ward, String bed, String glove) {
        String prefix = WARD_PREFIX.get(ward);
        if (prefix == null || !bed.matches("B-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("Select a valid ward and bed.");
        String expected = "GLV-" + prefix + "-" + String.format("%03d", Integer.parseInt(bed.substring(2)));
        String bedId = ward + ":" + bed;
        if (!expected.equals(glove) || beds.findById(bedId).filter(item -> item.getGloveId().equals(glove)).isEmpty()) {
            throw new IllegalArgumentException("The selected bed is not linked to that glove.");
        }
    }

    private static String validated(String value, List<String> allowed, String field) {
        if (!allowed.contains(value)) throw new IllegalArgumentException("Invalid " + field + ".");
        return value;
    }

    private void event(PatientEntity patient, String type, String actor, String description, Instant timestamp) {
        PatientJourneyEntity event = new PatientJourneyEntity();
        event.setId(UUID.randomUUID().toString());
        event.setPatientId(patient.getId());
        event.setType(type);
        event.setActor(actor);
        event.setDescription(description);
        event.setTimestamp(timestamp);
        journey.save(event);
    }

    static PatientDtos.PatientResponse response(PatientEntity patient) {
        return new PatientDtos.PatientResponse(
                patient.getId(), patient.getName(), patient.getAge(), patient.getWard(), patient.getBed(),
                patient.getBedId(), patient.getGlove(), patient.getDoctor().getName(), patient.getDoctor().getId(),
                patient.getStatus(), patient.getBpm(), patient.getTemp(), patient.getGsr(),
                patient.isAuthorized(), patient.getGender(), patient.getContact(), patient.getEmergencyContact(),
                patient.getBloodGroup(), patient.getCondition(), patient.getAdmissionDate(), patient.getAdmissionTime(),
                patient.getLifecycle(), patient.getMonitoringStatus(), patient.getHospital());
    }
}
