package ai.careglove.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditLogRepository repository;
    private final ObjectMapper mapper;

    public AuditService(AuditLogRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void record(String actorId, String action, String resource, String resourceId, Map<String, ?> metadata) {
        AuditLogEntity event = new AuditLogEntity();
        event.setId(UUID.randomUUID().toString());
        event.setActorId(actorId);
        event.setAction(action);
        event.setResource(resource);
        event.setResourceId(resourceId);
        event.setCreatedAt(Instant.now());
        try {
            event.setMetadata(metadata == null ? "{}" : mapper.writeValueAsString(metadata));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit metadata could not be serialized.", exception);
        }
        repository.save(event);
    }
}
