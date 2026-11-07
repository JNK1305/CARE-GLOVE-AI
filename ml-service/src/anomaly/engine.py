from collections import deque
from datetime import datetime, timedelta

from src.baseline.robust import robust_deviations
from src.config.settings import Settings
from src.features.window import extract_window_features
from src.persistence.state import PersistenceTracker
from src.preprocessing.pipeline import SIGNALS, normalize_timestamp, reading_quality
from src.schemas.reading import (
    Contributor,
    DataQuality,
    PredictionResponse,
    SensorReading,
)


class DuplicateReadingError(ValueError):
    pass


class OutOfOrderReadingError(ValueError):
    pass


class PatientState:
    def __init__(self, settings: Settings):
        self.readings: deque[SensorReading] = deque(maxlen=settings.max_history)
        self.baseline: deque[dict[str, float]] = deque(maxlen=settings.max_history)
        self.last_timestamp: datetime | None = None
        self.last_window_end: datetime | None = None
        self.persistence = PersistenceTracker(settings)


class AnomalyEngine:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._patients: dict[str, PatientState] = {}

    def predict(self, reading: SensorReading) -> PredictionResponse:
        timestamp = normalize_timestamp(reading.timestamp)
        patient_id = reading.canonical_patient_id
        patient = self._patients.setdefault(patient_id, PatientState(self.settings))
        if patient.last_timestamp == timestamp:
            raise DuplicateReadingError("A reading for this patient and timestamp already exists.")
        if patient.last_timestamp is not None and timestamp < patient.last_timestamp:
            raise OutOfOrderReadingError("Reading timestamp is older than the last accepted reading.")
        normalized = reading.model_copy(update={"timestamp": timestamp})
        patient.last_timestamp = timestamp
        patient.readings.append(normalized)
        valid, missing, reasons = reading_quality(normalized)
        cutoff = timestamp - timedelta(seconds=self.settings.window_seconds)
        window = [entry for entry in patient.readings if normalize_timestamp(entry.timestamp) >= cutoff]
        complete_window = (
            len(window) >= 2
            and (timestamp - normalize_timestamp(window[0].timestamp)).total_seconds() >= self.settings.window_seconds
        )
        quality_reasons = list(reasons)
        if not complete_window:
            quality_reasons.append("insufficient_window")
        if len(patient.baseline) < self.settings.baseline_windows:
            quality_reasons.append("insufficient_personal_baseline")
        quality = DataQuality(
            valid=valid and complete_window,
            missing_signals=missing,
            reasons=quality_reasons,
            window_readings=len(window),
        )
        state = "MONITOR"
        score: float | None = None
        contributors: list[Contributor] = []
        should_score = (
            valid
            and complete_window
            and (patient.last_window_end is None or (timestamp - patient.last_window_end).total_seconds() >= self.settings.stride_seconds)
        )
        if should_score:
            features = extract_window_features(window)
            if len(patient.baseline) >= self.settings.baseline_windows:
                score, raw_contributors = robust_deviations(features, list(patient.baseline))
                contributors = [Contributor.model_validate(item) for item in raw_contributors]
                if score >= self.settings.anomaly_z:
                    state = "POTENTIAL_ANOMALY"
                elif score >= self.settings.monitor_z:
                    state = "MONITOR"
                else:
                    state = "NORMAL"
                if state == "NORMAL":
                    patient.baseline.append(features)
            elif features:
                patient.baseline.append(features)
            patient.last_window_end = timestamp
        scored_window = should_score and complete_window and valid
        persistence = (
            patient.persistence.update(patient_id, timestamp, state == "POTENTIAL_ANOMALY")
            if scored_window
            else patient.persistence.current(patient_id)
        )
        if persistence.started_at is not None:
            persistence.duration_seconds = max(0.0, (timestamp - persistence.started_at).total_seconds())
        return PredictionResponse(
            patient_id=patient_id,
            timestamp=timestamp,
            state=state,
            anomaly_score=score,
            model_version=self.settings.model_version,
            contributors=contributors,
            data_quality=quality,
            persistence=persistence,
            scored_window=scored_window,
        )
