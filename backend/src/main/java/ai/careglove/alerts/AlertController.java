package ai.careglove.alerts;

import ai.careglove.user.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AlertController {
    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping("/api/alerts")
    public List<AlertDtos.AlertResponse> list(@AuthenticationPrincipal UserEntity user) {
        return service.list(user);
    }

    @GetMapping("/api/patients/{patientId}/alerts")
    public List<AlertDtos.AlertResponse> patientAlerts(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        return service.forPatient(patientId, user);
    }

    @PatchMapping("/api/alerts/{alertId}")
    public AlertDtos.AlertResponse update(
            @PathVariable String alertId,
            @RequestBody(required = false) StatusRequest request,
            @RequestParam(name = "status", required = false) String paramStatus,
            @AuthenticationPrincipal UserEntity user) {
        String status = (request != null && request.status() != null && !request.status().isBlank())
                ? request.status()
                : paramStatus;
        if (status == null || status.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Status is required.");
        }
        return service.update(alertId, status, user);
    }

    public record StatusRequest(String status) {}
}
