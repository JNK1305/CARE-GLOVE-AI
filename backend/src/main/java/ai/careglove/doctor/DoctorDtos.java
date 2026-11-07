package ai.careglove.doctor;

import ai.careglove.common.Role;
import ai.careglove.user.UserEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class DoctorDtos {
    private DoctorDtos() {}

    public record CreateDoctorRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 120) String hospital,
            @Size(max = 120) String specialty,
            @Size(max = 40) String contact,
            @Size(max = 64) String employeeId,
            @Size(min = 12, max = 200) String password) {}

    public record UpdateDoctorRequest(
            @Size(max = 160) String name,
            @Size(max = 120) String specialty,
            @Size(max = 40) String contact,
            @Size(max = 64) String employeeId,
            Boolean active) {}

    public record DoctorResponse(
            String id,
            String name,
            String email,
            Role role,
            String hospital,
            String specialty,
            String contact,
            String employeeId,
            boolean active,
            int activePatientsCount) {
        public static DoctorResponse from(UserEntity user, int activePatientsCount) {
            return new DoctorResponse(
                    user.getId(),
                    user.getName(),
                    user.getEmail(),
                    user.getRole(),
                    user.getHospital(),
                    user.getSpecialty() != null ? user.getSpecialty() : "General Medicine",
                    user.getContact(),
                    user.getEmployeeId(),
                    user.isActive(),
                    activePatientsCount);
        }
    }
}
