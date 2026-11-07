package ai.careglove.clinical;

import ai.careglove.user.UserEntity;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients/{patientId}/clinical-records")
public class ClinicalRecordController {
    private final ClinicalRecordService service;

    public ClinicalRecordController(ClinicalRecordService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClinicalRecordDtos.RecordResponse> list(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        return service.list(patientId, user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicalRecordDtos.RecordResponse create(
            @PathVariable String patientId,
            @Valid @RequestBody ClinicalRecordDtos.CreateRequest request,
            @AuthenticationPrincipal UserEntity user) {
        return service.create(patientId, request, user);
    }

    @PutMapping("/{recordId}")
    public ClinicalRecordDtos.RecordResponse update(
            @PathVariable String patientId,
            @PathVariable String recordId,
            @Valid @RequestBody ClinicalRecordDtos.UpdateRequest request,
            @AuthenticationPrincipal UserEntity user) {
        return service.update(patientId, recordId, request, user);
    }
}
