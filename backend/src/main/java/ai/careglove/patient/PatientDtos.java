package ai.careglove.patient;

import jakarta.validation.constraints.*;

public final class PatientDtos {
    private PatientDtos() {}

    public record CreatePatientRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 32) String age,
            @NotBlank @Size(max = 40) String ward,
            @NotBlank @Size(max = 16) String bed,
            @NotBlank @Size(max = 80) String glove,
            @NotBlank @Size(max = 64) String doctorId,
            @NotBlank @Size(max = 500) String condition,
            String gender,
            String contact,
            String emergencyContact,
            String bloodGroup,
            String admissionDate,
            String admissionTime,
            Boolean authorized,
            String hospital) {
        public CreatePatientRequest(String name, String age, String ward, String bed, String glove,
                String doctorId, String condition, String gender, String contact, String emergencyContact,
                String bloodGroup, String admissionDate, String admissionTime, Boolean authorized) {
            this(name, age, ward, bed, glove, doctorId, condition, gender, contact, emergencyContact,
                    bloodGroup, admissionDate, admissionTime, authorized, null);
        }
    }

    public record UpdatePatientRequest(
            @Size(max = 160) String name,
            @Size(max = 32) String age,
            @Size(max = 40) String ward,
            @Size(max = 16) String bed,
            @Size(max = 80) String glove,
            @Size(max = 64) String doctorId,
            @Size(max = 500) String condition,
            String gender,
            String contact,
            String emergencyContact,
            String bloodGroup,
            String admissionDate,
            String admissionTime,
            String lifecycle,
            String monitoringStatus,
            Boolean authorized,
            String status) {}

    public record PatientResponse(
            String id,
            String name,
            String age,
            String ward,
            String bed,
            String bedId,
            String glove,
            String doctor,
            String doctorId,
            String status,
            Double bpm,
            Double temp,
            Double gsr,
            boolean authorized,
            String gender,
            String contact,
            String emergencyContact,
            String bloodGroup,
            String condition,
            String admissionDate,
            String admissionTime,
            String lifecycle,
            String monitoringStatus,
            String hospital) {}
}
