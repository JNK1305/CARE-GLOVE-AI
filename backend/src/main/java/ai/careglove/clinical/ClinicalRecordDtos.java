package ai.careglove.clinical;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ClinicalRecordDtos {
    private ClinicalRecordDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 32) String type,
            String content,
            String medicineName,
            String dosage,
            String frequency,
            String instruction,
            String testName,
            String result,
            String notes) {}

    public record UpdateRequest(
            @NotBlank String content) {}

    public record RecordResponse(
            String id,
            String patientId,
            String actorId,
            String actorName,
            String actorRole,
            String type,
            String content,
            String medicineName,
            String dosage,
            String frequency,
            String instruction,
            String testName,
            String result,
            String notes,
            String hospital,
            Instant updatedAt,
            boolean isInternal,
            Instant timestamp) {
        static RecordResponse from(ClinicalRecordEntity record) {
            return new RecordResponse(record.getId(), record.getPatientId(), record.getActorId(), record.getActorName(),
                    record.getActorRole(), record.getType(), record.getContent(), record.getMedicineName(),
                    record.getDosage(), record.getFrequency(), record.getInstruction(), record.getTestName(),
                    record.getResult(), record.getNotes(), record.getHospital(), record.getUpdatedAt(),
                    record.isInternal(), record.getTimestamp());
        }
    }
}
