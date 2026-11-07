from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from src.anomaly.engine import AnomalyEngine, DuplicateReadingError, OutOfOrderReadingError
from src.config.settings import Settings
from src.features.window import extract_window_features
from src.main import app
from src.preprocessing.pipeline import SIGNALS
from src.schemas.reading import SensorReading
from src.simulation.generator import stable_readings


def settings(**overrides: object) -> Settings:
    values = {
        "model_version": "test-mvp",
        "window_seconds": 60,
        "stride_seconds": 15,
        "baseline_windows": 2,
        "monitor_z": 2.0,
        "anomaly_z": 4.0,
        "alert_persistence_windows": 2,
        "recovery_windows": 2,
        "alert_cooldown_seconds": 60,
        "max_history": 100,
    }
    values.update(overrides)
    return Settings(**values)


def reading(patient: str, seconds: int, **values: object) -> SensorReading:
    data = {
        "user_id": patient,
        "timestamp": datetime(2026, 10, 3, tzinfo=timezone.utc) + timedelta(seconds=seconds),
        "heart_rate": 75.0,
        "gsr": 0.42,
        "temperature": 36.7,
    }
    data.update(values)
    return SensorReading(**data)


def test_insufficient_baseline_then_deterministic_personalized_scoring():
    engine = AnomalyEngine(settings())
    stable = stable_readings("patient-a", datetime(2026, 10, 3, tzinfo=timezone.utc), 10)
    responses = [engine.predict(item) for item in stable]
    assert responses[0].state == "MONITOR"
    assert responses[-1].state == "NORMAL"
    assert responses[-1].anomaly_score == 0
    with pytest.raises(DuplicateReadingError):
        engine.predict(stable[-1])


def test_out_of_order_reading_is_rejected():
    engine = AnomalyEngine(settings())
    engine.predict(reading("patient-a", 30))
    with pytest.raises(OutOfOrderReadingError):
        engine.predict(reading("patient-a", 0))


def test_feature_order_contains_exactly_the_three_supported_signals():
    features = extract_window_features([reading("patient-a", 0)])
    assert tuple(features) == SIGNALS == ("heart_rate", "gsr", "temperature")


def test_missing_or_invalid_sensor_values_are_rejected():
    client = TestClient(app)
    payload = {
        "user_id": "invalid-patient",
        "timestamp": "2026-10-03T10:30:00Z",
        "heart_rate": 78.0,
        "gsr": 0.42,
        "temperature": 36.7,
    }
    assert client.post("/api/v1/predict", json={**payload, "gsr": None}).status_code == 422
    assert client.post("/api/v1/predict", json={key: value for key, value in payload.items() if key != "gsr"}).status_code == 422
    assert client.post("/api/v1/predict", json={**payload, "temperature": "not-a-number"}).status_code == 422
    assert client.post("/api/v1/predict", json={**payload, "heart_rate": "NaN"}).status_code == 422
    assert client.post("/api/v1/predict", json={**payload, "spo2": 97.0}).status_code == 422


def test_invalid_timestamp_requires_timezone():
    with pytest.raises(ValueError):
        reading("patient-a", 0, timestamp="2026-10-03T10:30:00")


def test_anomaly_requires_configured_persistence_then_recovers():
    engine = AnomalyEngine(settings())
    baseline = stable_readings("patient-a", datetime(2026, 10, 3, tzinfo=timezone.utc), 10)
    for item in baseline:
        engine.predict(item)
    anomalous = engine.predict(reading("patient-a", 300, heart_rate=180.0))
    assert anomalous.state == "POTENTIAL_ANOMALY"
    assert anomalous.persistence.active is True
    alert = engine.predict(reading("patient-a", 330))
    assert alert.persistence.alert_eligible is True
    cooling_down = engine.predict(reading("patient-a", 360))
    assert cooling_down.persistence.alert_eligible is False
    engine.predict(reading("patient-a", 390))
    recovered = engine.predict(reading("patient-a", 420))
    assert recovered.persistence.active is False
    final = recovered
    assert final.persistence.recovered_at is not None


def test_api_valid_invalid_duplicate_batch_and_model_info():
    client = TestClient(app)
    assert client.get("/health").json()["status"] == "UP"
    assert client.get("/ready").json()["status"] == "READY"
    info = client.get("/api/v1/model/info").json()
    assert info["window_seconds"] == 60
    assert info["feature_order"] == ["heart_rate", "gsr", "temperature"]
    payload = {
        "user_id": "api-patient",
        "timestamp": "2026-10-03T10:30:00Z",
        "heart_rate": 78.0,
        "gsr": 0.42,
        "temperature": 36.7,
    }
    prediction = client.post("/api/v1/predict", json=payload)
    assert prediction.status_code == 200
    assert prediction.json()["severity"] == "SENSOR_UNAVAILABLE"
    for index in range(1, 4):
        current = {**payload, "timestamp": f"2026-10-03T10:30:{index * 15:02d}Z"}
        prediction = client.post("/api/v1/predict", json=current)
    assert prediction.json()["severity"] in {"NORMAL", "WARNING", "CRITICAL"}
    assert prediction.json()["classifier_version"] == "synthetic-scenario-v1"
    assert client.post("/api/v1/predict", json=payload).status_code == 409
    batch = {
        "readings": [
            {**payload, "user_id": "batch-patient", "timestamp": "2026-10-03T10:31:00Z"},
            {**payload, "user_id": "batch-patient", "timestamp": "2026-10-03T10:30:00Z"},
        ]
    }
    result = client.post("/api/v1/predict/batch", json=batch)
    assert result.status_code == 200
    assert result.json()[0]["timestamp"] < result.json()[1]["timestamp"]


def test_sensor_contract_keeps_source_metadata_optional_and_validates_patient_alias():
    assert reading("canonical", 0, patient_id="canonical").canonical_patient_id == "canonical"
    with pytest.raises(ValueError):
        reading("canonical", 0, patient_id="other")
