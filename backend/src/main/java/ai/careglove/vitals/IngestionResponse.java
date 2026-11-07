package ai.careglove.vitals;

import ai.careglove.alerts.AlertDtos;
import ai.careglove.ml.MlDtos;

public record IngestionResponse(
        SensorReadingResponse reading,
        MlDtos.PredictionResponse prediction,
        AlertDtos.AlertResponse alert,
        String processingStatus,
        String message) {}
