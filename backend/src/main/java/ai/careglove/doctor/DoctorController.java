package ai.careglove.doctor;

import ai.careglove.user.UserEntity;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {
    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    @GetMapping
    public List<DoctorDtos.DoctorResponse> listDoctors(@AuthenticationPrincipal UserEntity caller) {
        return doctorService.listDoctors(caller);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public DoctorDtos.DoctorResponse createDoctor(
            @Valid @RequestBody DoctorDtos.CreateDoctorRequest request,
            @AuthenticationPrincipal UserEntity actor) {
        return doctorService.createDoctor(request, actor);
    }

    @PatchMapping("/{doctorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public DoctorDtos.DoctorResponse updateDoctor(
            @PathVariable String doctorId,
            @Valid @RequestBody DoctorDtos.UpdateDoctorRequest request,
            @AuthenticationPrincipal UserEntity actor) {
        return doctorService.updateDoctor(doctorId, request, actor);
    }
}
