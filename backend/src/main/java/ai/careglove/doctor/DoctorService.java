package ai.careglove.doctor;

import ai.careglove.audit.AuditService;
import ai.careglove.common.Role;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DoctorService {
    private final UserRepository users;
    private final PatientRepository patients;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private static final SecureRandom RANDOM = new SecureRandom();

    public DoctorService(
            UserRepository users,
            PatientRepository patients,
            PasswordEncoder passwordEncoder,
            AuditService audit) {
        this.users = users;
        this.patients = patients;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DoctorDtos.DoctorResponse> listDoctors(UserEntity caller) {
        String hospital = caller.getHospital();
        List<UserEntity> doctorList;
        if (hospital != null && !hospital.isBlank()) {
            doctorList = users.findByHospitalAndRoleAndActiveTrueOrderByNameAsc(hospital, Role.senior);
        } else {
            doctorList = users.findByRoleAndActiveTrueOrderByNameAsc(Role.senior);
        }
        return doctorList.stream()
                .map(doctor -> {
                    int count = patients.findByDoctorIdAndLifecycleNotOrderByNameAsc(doctor.getId(), "DISCHARGED").size();
                    return DoctorDtos.DoctorResponse.from(doctor, count);
                })
                .toList();
    }

    @Transactional
    public DoctorDtos.DoctorResponse createDoctor(DoctorDtos.CreateDoctorRequest request, UserEntity actor) {
        if (actor.getRole() != Role.admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only hospital administrators can add doctors.");
        }

        String targetHospital = request.hospital().trim();
        if (actor.getHospital() != null && !actor.getHospital().isBlank()
                && !actor.getHospital().equalsIgnoreCase(targetHospital)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Administrators can only add doctors to their assigned hospital: " + actor.getHospital());
        }

        String email = request.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with email '" + email + "' already exists.");
        }

        if (request.employeeId() != null && !request.employeeId().isBlank()) {
            String empId = request.employeeId().trim();
            if (users.findByEmployeeIdAndHospital(empId, targetHospital).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A doctor with employee ID '" + empId + "' already exists in " + targetHospital + ".");
            }
        }

        String rawPassword = request.password();
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = generateSecurePassword();
        } else if (rawPassword.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor password must be at least 12 characters.");
        }

        String generatedId = generateDoctorId(request.name());
        while (users.existsById(generatedId)) {
            generatedId = generateDoctorId(request.name());
        }

        UserEntity doctor = new UserEntity();
        doctor.setId(generatedId);
        doctor.setName(request.name().trim());
        doctor.setEmail(email);
        doctor.setRole(Role.senior);
        doctor.setHospital(targetHospital);
        doctor.setSpecialty(request.specialty() != null && !request.specialty().isBlank() ? request.specialty().trim() : "General Medicine");
        doctor.setContact(request.contact() != null && !request.contact().isBlank() ? request.contact().trim() : null);
        doctor.setEmployeeId(request.employeeId() != null && !request.employeeId().isBlank() ? request.employeeId().trim() : null);
        doctor.setPasswordHash(passwordEncoder.encode(rawPassword));
        doctor.setActive(true);

        doctor = users.save(doctor);

        audit.record(actor.getId(), "DOCTOR_CREATE", "USER", doctor.getId(), Map.of(
                "name", doctor.getName(),
                "hospital", doctor.getHospital(),
                "specialty", doctor.getSpecialty() != null ? doctor.getSpecialty() : "General Medicine"
        ));

        return DoctorDtos.DoctorResponse.from(doctor, 0);
    }

    @Transactional
    public DoctorDtos.DoctorResponse updateDoctor(String doctorId, DoctorDtos.UpdateDoctorRequest request, UserEntity actor) {
        if (actor.getRole() != Role.admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only hospital administrators can update doctors.");
        }

        UserEntity doctor = users.findById(doctorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found."));

        if (doctor.getRole() != Role.senior) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The specified user is not a doctor.");
        }

        if (actor.getHospital() != null && !actor.getHospital().isBlank()
                && !actor.getHospital().equalsIgnoreCase(doctor.getHospital())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Administrators can only update doctors within their assigned hospital.");
        }

        if (request.name() != null && !request.name().isBlank()) {
            doctor.setName(request.name().trim());
        }
        if (request.specialty() != null) {
            doctor.setSpecialty(request.specialty().trim());
        }
        if (request.contact() != null) {
            doctor.setContact(request.contact().trim());
        }
        if (request.employeeId() != null && !request.employeeId().isBlank()) {
            String empId = request.employeeId().trim();
            Optional<UserEntity> existing = users.findByEmployeeIdAndHospital(empId, doctor.getHospital());
            if (existing.isPresent() && !existing.get().getId().equals(doctor.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A doctor with employee ID '" + empId + "' already exists in " + doctor.getHospital() + ".");
            }
            doctor.setEmployeeId(empId);
        }
        if (request.active() != null) {
            doctor.setActive(request.active());
        }

        doctor = users.save(doctor);

        audit.record(actor.getId(), "DOCTOR_UPDATE", "USER", doctor.getId(), Map.of(
                "hospital", doctor.getHospital(),
                "active", String.valueOf(doctor.isActive())
        ));

        int count = patients.findByDoctorIdAndLifecycleNotOrderByNameAsc(doctor.getId(), "DISCHARGED").size();
        return DoctorDtos.DoctorResponse.from(doctor, count);
    }

    private String generateDoctorId(String name) {
        String clean = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (clean.length() > 8) clean = clean.substring(0, 8);
        if (clean.isBlank()) clean = "doc";
        String suffix = Integer.toHexString(RANDOM.nextInt(0x10000));
        return clean + "-" + suffix;
    }

    private String generateSecurePassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
