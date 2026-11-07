package ai.careglove.reports;

import ai.careglove.user.UserEntity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/api/reports")
    public List<ReportDtos.ReportResponse> list(@AuthenticationPrincipal UserEntity user) {
        return service.list(user);
    }

    @GetMapping("/api/reports/{reportId}")
    public ReportDtos.ReportResponse get(
            @PathVariable String reportId,
            @AuthenticationPrincipal UserEntity user) {
        return service.get(reportId, user);
    }

    @PostMapping("/api/patients/{patientId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDtos.ReportResponse generate(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        return service.generate(patientId, user);
    }
}
