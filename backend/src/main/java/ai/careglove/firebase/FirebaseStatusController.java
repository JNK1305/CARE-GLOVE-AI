package ai.careglove.firebase;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class FirebaseStatusController {
    private final FirebaseIngestionService ingestionService;

    public FirebaseStatusController(FirebaseIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @GetMapping("/firebase-status")
    public FirebaseIngestionService.FirebaseStatus getStatus() {
        return ingestionService.getStatus();
    }

    @PostMapping("/firebase-sync")
    public FirebaseIngestionService.IngestionResult sync() {
        return ingestionService.pollOnce();
    }
}
