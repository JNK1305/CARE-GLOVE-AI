from collections.abc import Sequence

from src.preprocessing.pipeline import SIGNALS
from src.schemas.reading import SensorReading


def extract_window_features(readings: Sequence[SensorReading]) -> dict[str, float]:
    features: dict[str, float] = {}
    for signal in SIGNALS:
        values = [getattr(reading, signal) for reading in readings]
        available = [value for value in values if value is not None]
        if available:
            features[signal] = sum(available) / len(available)
    return features
