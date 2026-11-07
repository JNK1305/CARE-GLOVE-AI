from pathlib import Path

import pytest

from src.model.runtime import ScenarioModel
from src.model.synthetic import SIGNALS, generate_runs, windows
from src.model.training import train_model


def test_synthetic_runs_are_reproducible_and_have_three_features():
    first = generate_runs(run_count=8, seed=14)
    second = generate_runs(run_count=8, seed=14)
    assert first == second
    features, _ = next(windows(first["run-0000"]))
    assert len(features) == len(SIGNALS) == 3


def test_training_splits_sequences_and_model_loads(tmp_path: Path):
    metrics = train_model(tmp_path, run_count=24, seed=29)
    assert metrics["split"]["train_runs"] + metrics["split"]["test_runs"] == 24
    assert metrics["split"]["train_windows"] > metrics["split"]["test_windows"]
    assert (tmp_path / "synthetic_sensor_windows.csv").is_file()
    model = ScenarioModel(tmp_path / "sensor_scenario_model.joblib")
    prediction = model.classify({"heart_rate": 180.0, "gsr": 12.0, "temperature": 39.0})
    assert prediction["severity"] in {"NORMAL", "WARNING", "CRITICAL"}
    assert 0 <= prediction["confidence"] <= 1


def test_model_rejects_incorrect_feature_order(tmp_path: Path):
    train_model(tmp_path, run_count=12, seed=31)
    model = ScenarioModel(tmp_path / "sensor_scenario_model.joblib")
    with pytest.raises(ValueError, match="Expected features in order"):
        model.classify({"gsr": 1.0, "heart_rate": 80.0, "temperature": 36.8})
