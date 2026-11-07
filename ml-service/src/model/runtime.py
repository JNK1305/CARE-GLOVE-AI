from pathlib import Path
from typing import Any

import joblib
import numpy as np

from src.preprocessing.pipeline import SIGNALS

MODEL_PATH = Path(__file__).resolve().parents[2] / "artifacts" / "sensor_scenario_model.joblib"


class ScenarioModel:
    def __init__(self, path: Path = MODEL_PATH):
        if not path.is_file():
            raise FileNotFoundError(
                f"Trained scenario model not found at {path}. Run `python -m src.model.train` first."
            )
        artifact = joblib.load(path)
        if tuple(artifact.get("feature_order", ())) != SIGNALS:
            raise ValueError("Trained model feature order does not match the supported sensor signals.")
        self.pipeline = artifact["pipeline"]
        self.version = artifact["version"]
        self.window_size = int(artifact["window_size"])

    def classify(self, features: dict[str, float]) -> dict[str, Any]:
        if tuple(features) != SIGNALS:
            raise ValueError(f"Expected features in order: {', '.join(SIGNALS)}.")
        vector = np.asarray([[features[name] for name in SIGNALS]], dtype=np.float64)
        predicted = str(self.pipeline.predict(vector)[0])
        probabilities = self.pipeline.predict_proba(vector)[0]
        classes = self.pipeline.classes_
        confidence = float(probabilities[list(classes).index(predicted)])
        return {
            "severity": predicted,
            "confidence": confidence,
            "classifier_version": self.version,
            "model_version": self.version,
        }
