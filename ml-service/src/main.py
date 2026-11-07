from contextlib import asynccontextmanager
from collections import defaultdict, deque
from datetime import timezone
from fastapi import FastAPI, HTTPException

from src.anomaly.engine import AnomalyEngine, DuplicateReadingError, OutOfOrderReadingError
from src.config.settings import Settings
from src.features.window import extract_window_features
from src.model.runtime import ScenarioModel
from src.preprocessing.pipeline import SIGNALS
from src.schemas.reading import (
    BatchPredictionRequest,
    ModelInfo,
    PredictionResponse,
    SensorReading,
)

settings = Settings.from_env()
engine = AnomalyEngine(settings)
scenario_model = ScenarioModel()
scenario_windows: dict[str, deque[SensorReading]] = defaultdict(
    lambda: deque(maxlen=scenario_model.window_size)
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield


app = FastAPI(
    title="CARE-GLOVE AI ML Service",
    version=settings.model_version,
    description="Source-agnostic statistical monitoring research prototype; not for diagnosis or treatment.",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "care-glove-ml"}


@app.get("/ready")
def ready() -> dict[str, object]:
    is_ready = scenario_model is not None and scenario_model.pipeline is not None
    return {
        "status": "READY" if is_ready else "NOT_READY",
        "service": "care-glove-ml",
        "model_version": settings.model_version,
        "classifier_version": scenario_model.version if scenario_model else "unavailable",
    }


@app.get("/api/v1/model/info", response_model=ModelInfo)
def model_info() -> ModelInfo:
    return ModelInfo(
        model_version=settings.model_version,
        method="Personalized rolling median/MAD robust statistical deviation",
        classifier_version=scenario_model.version,
        feature_order=list(SIGNALS),
        classifier_labels=["NORMAL", "WARNING", "CRITICAL"],
        window_seconds=settings.window_seconds,
        stride_seconds=settings.stride_seconds,
        baseline_windows=settings.baseline_windows,
        monitor_z=settings.monitor_z,
        anomaly_z=settings.anomaly_z,
        persistence_windows=settings.alert_persistence_windows,
        recovery_windows=settings.recovery_windows,
    )


def predict(reading: SensorReading) -> PredictionResponse:
    try:
        result = engine.predict(reading)
        history = scenario_windows[reading.canonical_patient_id]
        history.append(reading)
        if len(history) < scenario_model.window_size:
            classification = {
                "severity": "SENSOR_UNAVAILABLE",
                "confidence": None,
                "classifier_version": scenario_model.version,
                "model_version": scenario_model.version,
            }
            reason = f"Waiting for {scenario_model.window_size - len(history)} more readings to fill the classifier window."
        else:
            classification = scenario_model.classify(extract_window_features(history))
            reason = (
                f"Synthetic scenario model classified the BPM/GSR/temperature window as "
                f"{classification['severity']}."
            )
        return result.model_copy(
            update={
                **classification,
                "reason": reason,
            }
        )
    except DuplicateReadingError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except OutOfOrderReadingError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@app.post("/api/v1/predict", response_model=PredictionResponse)
def predict_one(reading: SensorReading) -> PredictionResponse:
    return predict(reading)


@app.post("/api/v1/predict/batch", response_model=list[PredictionResponse])
def predict_batch(request: BatchPredictionRequest) -> list[PredictionResponse]:
    ordered = sorted(request.readings, key=lambda item: (item.canonical_patient_id, item.timestamp.astimezone(timezone.utc)))
    return [predict(reading) for reading in ordered]
