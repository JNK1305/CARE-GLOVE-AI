package ai.careglove.clinical;

import ai.careglove.audit.AuditService;
import ai.careglove.common.Role;
import ai.careglove.journey.PatientJourneyEntity;
import ai.careglove.journey.PatientJourneyRepository;
import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.user.UserEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClinicalRecordService {
    private static final Set<String> TYPES = Set.of(
            "NOTE", "MEDICINE_INSTRUCTION", "SUGGESTION", "OBSERVATION", "REPORT", "TEST", "ACTION", "PATIENT_RESPONSE");
    private static final Set<String> DUTY_TYPES = Set.of("OBSERVATION", "REPORT", "TEST", "ACTION", "PATIENT_RESPONSE");
    private final ClinicalRecordRepository records;
    private final PatientAccessService patientAccess;
    private final PatientJourneyRepository journey;
    private final AuditService audit;

    public ClinicalRecordService(
            ClinicalRecordRepository records,
            PatientAccessService patientAccess,
            PatientJourneyRepository journey,
            AuditService audit) {
        this.records = records;
        this.patientAccess = patientAccess;
        this.journey = journey;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ClinicalRecordDtos.RecordResponse> list(String patientId, UserEntity user) {
        PatientEntity patient = patientAccess.getVisible(patientId, user);
        return records.findByPatientIdOrderByTimestampDesc(patientId).stream()
                .filter(r -> r.getHospital() == null || user.getHospital() == null || r.getHospital().equalsIgnoreCase(user.getHospital()))
                .map(ClinicalRecordDtos.RecordResponse::from)
                .toList();
    }

    @Transactional
    public ClinicalRecordDtos.RecordResponse create(String patientId, ClinicalRecordDtos.CreateRequest request, UserEntity actor) {
        PatientEntity patient = patientAccess.getVisible(patientId, actor);
        if (actor.getHospital() != null && patient.getHospital() != null
                && !actor.getHospital().equalsIgnoreCase(patient.getHospital())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-hospital record creation is forbidden.");
        }
        if (!TYPES.contains(request.type())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported clinical record type.");
        if (actor.getRole() == Role.duty && !DUTY_TYPES.contains(request.type())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "On-duty staff cannot create this clinical record type.");
        }
        if (request.type().equals("TEST") && blank(request.testName(), request.result())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test name and result are required.");
        }
        if (request.type().equals("MEDICINE_INSTRUCTION") && blank(request.medicineName(), request.dosage())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Medicine name and dosage are required.");
        }
        if (!request.type().equals("TEST") && !request.type().equals("MEDICINE_INSTRUCTION")
                && (request.content() == null || request.content().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clinical record content is required.");
        }
        if (request.content() != null && request.content().length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clinical record content exceeds maximum length (5000 characters).");
        }
        Instant now = Instant.now();
        ClinicalRecordEntity record = new ClinicalRecordEntity();
        record.setId(UUID.randomUUID().toString());
        record.setPatientId(patientId);
        record.setActorId(actor.getId());
        record.setActorName(actor.getName());
        record.setActorRole(actor.getRole().name());
        record.setType(request.type());
        record.setContent(request.content());
        record.setMedicineName(request.medicineName());
        record.setDosage(request.dosage());
        record.setFrequency(request.frequency());
        record.setInstruction(request.instruction());
        record.setTestName(request.testName());
        record.setResult(request.result());
        record.setNotes(request.notes());
        record.setHospital(patient.getHospital() != null ? patient.getHospital() : (actor.getHospital() != null ? actor.getHospital() : "CARE Hospital"));
        record.setInternal(true);
        record.setTimestamp(now);
        record = records.save(record);
        PatientJourneyEntity event = new PatientJourneyEntity();
        event.setId(UUID.randomUUID().toString());
        event.setPatientId(patientId);
        event.setType(record.getType());
        event.setActor(actor.getName());
        event.setDescription("Added clinical record: " + record.getType());
        event.setTimestamp(now);
        journey.save(event);
        audit.record(actor.getId(), "CLINICAL_RECORD_CREATE", "CLINICAL_RECORD", record.getId(), Map.of("type", record.getType()));
        return ClinicalRecordDtos.RecordResponse.from(record);
    }

    @Transactional
    public ClinicalRecordDtos.RecordResponse update(String patientId, String recordId, ClinicalRecordDtos.UpdateRequest request, UserEntity actor) {
        PatientEntity patient = patientAccess.getVisible(patientId, actor);
        if (actor.getHospital() != null && patient.getHospital() != null
                && !actor.getHospital().equalsIgnoreCase(patient.getHospital())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-hospital record update is forbidden.");
        }
        ClinicalRecordEntity record = records.findByIdAndPatientId(recordId, patientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinical record not found."));

        boolean isAuthor = actor.getId().equals(record.getActorId());
        boolean isAdmin = actor.getRole() == Role.admin;
        boolean isAssignedDoctor = actor.getRole() == Role.senior && patient.getDoctor().getId().equals(actor.getId());
        if (!isAuthor && !isAdmin && !isAssignedDoctor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to edit this clinical note.");
        }
        if (request.content() == null || request.content().trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note content cannot be empty.");
        }
        if (request.content().length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note content exceeds maximum length (5000 characters).");
        }

        record.setContent(request.content().trim());
        record.setUpdatedAt(Instant.now());
        record = records.save(record);

        audit.record(actor.getId(), "CLINICAL_NOTE_UPDATE", "CLINICAL_RECORD", record.getId(),
                Map.of("patientId", patientId, "authorId", record.getActorId()));

        return ClinicalRecordDtos.RecordResponse.from(record);
    }

    private boolean blank(String first, String second) {
        return first == null || first.isBlank() || second == null || second.isBlank();
    }
}
