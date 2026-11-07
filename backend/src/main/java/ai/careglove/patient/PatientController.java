package ai.careglove.patient;

import ai.careglove.user.UserEntity;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients")
public class PatientController {
    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    public List<PatientDtos.PatientResponse> list(@AuthenticationPrincipal UserEntity user) {
        return service.list(user);
    }

    @GetMapping("/{patientId}")
    public PatientDtos.PatientResponse get(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        return service.get(patientId, user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientDtos.PatientResponse create(
            @Valid @RequestBody PatientDtos.CreatePatientRequest request,
            @AuthenticationPrincipal UserEntity user) {
        return service.create(request, user);
    }

    @PatchMapping("/{patientId}")
    public PatientDtos.PatientResponse update(
            @PathVariable String patientId,
            @Valid @RequestBody PatientDtos.UpdatePatientRequest request,
            @AuthenticationPrincipal UserEntity user) {
        return service.update(patientId, request, user);
    }
}
