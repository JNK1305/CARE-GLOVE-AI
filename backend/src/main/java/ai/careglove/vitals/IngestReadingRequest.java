package ai.careglove.vitals;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record IngestReadingRequest(
        @NotBlank @Size(max = 64) String patientId,
        @NotBlank @Size(max = 80) String deviceId,
        @NotNull Instant timestamp,
        @NotNull @DecimalMin("-1000000.0") @DecimalMax("1000000.0") Double heartRate,
        @NotNull @DecimalMin("-1000000.0") @DecimalMax("1000000.0") Double gsr,
        @NotNull @DecimalMin("-1000000.0") @DecimalMax("1000000.0") Double temperature,
        @Size(max = 64) String source,
        @DecimalMin("0.0") @DecimalMax("1.0") Double signalQuality,
        @Size(max = 64) String firmwareVersion) {}
