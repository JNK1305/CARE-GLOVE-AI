package ai.careglove.journey;

import ai.careglove.patient.PatientAccessService;
import ai.careglove.user.UserEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients/{patientId}/journey")
public class JourneyController {
    private final PatientJourneyRepository journey;
    private final PatientAccessService patientAccess;

    public JourneyController(PatientJourneyRepository journey, PatientAccessService patientAccess) {
        this.journey = journey;
        this.patientAccess = patientAccess;
    }

    public record JourneyResponse(
            String id,
            String patientId,
            String type,
            String actor,
            String description,
            Instant timestamp,
            String severity,
            String metadata) {
        static JourneyResponse from(PatientJourneyEntity event) {
            return new JourneyResponse(event.getId(), event.getPatientId(), event.getType(), event.getActor(),
                    event.getDescription(), event.getTimestamp(), event.getSeverity(), event.getMetadata());
        }
    }

    @GetMapping
    public List<JourneyResponse> list(
            @PathVariable String patientId,
            @AuthenticationPrincipal UserEntity user) {
        patientAccess.getVisible(patientId, user);
        return journey.findByPatientIdOrderByTimestampAsc(patientId).stream().map(JourneyResponse::from).toList();
    }
}
