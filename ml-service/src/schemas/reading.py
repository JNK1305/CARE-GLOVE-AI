from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class SensorReading(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(min_length=1, max_length=128)
    timestamp: datetime
    heart_rate: float = Field(allow_inf_nan=False)
    gsr: float = Field(allow_inf_nan=False)
    temperature: float = Field(allow_inf_nan=False)
    patient_id: str | None = Field(default=None, min_length=1, max_length=128)
    device_id: str | None = Field(default=None, max_length=128)
    source: str | None = Field(default=None, max_length=64)
    received_at: datetime | None = None
    signal_quality: float | None = Field(default=None, ge=0.0, le=1.0)
    firmware_version: str | None = Field(default=None, max_length=64)

    @field_validator("timestamp", "received_at")
    @classmethod
    def require_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.utcoffset() is None:
            raise ValueError("timestamp must include a timezone")
        return value

    @model_validator(mode="after")
    def patient_alias_must_match_user(self) -> "SensorReading":
        if self.patient_id is not None and self.patient_id != self.user_id:
            raise ValueError("patient_id must match canonical user_id")
        return self

    @property
    def canonical_patient_id(self) -> str:
        return self.patient_id or self.user_id


class BatchPredictionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    readings: list[SensorReading] = Field(min_length=1, max_length=1000)


class DataQuality(BaseModel):
    valid: bool
    missing_signals: list[str] = Field(default_factory=list)
    reasons: list[str] = Field(default_factory=list)
    window_readings: int = 0


class Contributor(BaseModel):
    signal: str
    direction: Literal["ABOVE_BASELINE", "BELOW_BASELINE"]
    current_value: float
    baseline_value: float
    deviation: float
    contribution: float


class PersistenceResult(BaseModel):
    active: bool
    started_at: datetime | None = None
    duration_seconds: float = 0.0
    anomalous_window_count: int = 0
    last_alert_at: datetime | None = None
    cooldown_until: datetime | None = None
    recovered_at: datetime | None = None
    alert_eligible: bool = False


class PredictionResponse(BaseModel):
    patient_id: str
    timestamp: datetime
    state: Literal["NORMAL", "MONITOR", "POTENTIAL_ANOMALY"]
    severity: Literal["NORMAL", "WARNING", "CRITICAL", "SENSOR_UNAVAILABLE"] = "SENSOR_UNAVAILABLE"
    reason: str = "A complete classifier window is not yet available."
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    classifier_version: str = "unavailable"
    anomaly_score: float | None
    model_version: str
    contributors: list[Contributor]
    data_quality: DataQuality
    persistence: PersistenceResult
    scored_window: bool = False


class ModelInfo(BaseModel):
    model_version: str
    method: str
    classifier_version: str
    feature_order: list[str]
    classifier_labels: list[Literal["NORMAL", "WARNING", "CRITICAL"]]
    window_seconds: int
    stride_seconds: int
    baseline_windows: int
    monitor_z: float
    anomaly_z: float
    persistence_windows: int
    recovery_windows: int
    clinical_validation: Literal[False] = False
