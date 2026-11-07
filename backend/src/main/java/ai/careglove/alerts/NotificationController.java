package ai.careglove.alerts;

import ai.careglove.user.UserEntity;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public java.util.List<NotificationDtos.NotificationResponse> list(@AuthenticationPrincipal UserEntity user) {
        return service.list(user);
    }

    @PatchMapping("/{notificationId}")
    public NotificationDtos.NotificationResponse update(
            @PathVariable String notificationId,
            @Valid @RequestBody NotificationDtos.ReadRequest request,
            @AuthenticationPrincipal UserEntity user) {
        return service.update(notificationId, request.read(), user);
    }
}
