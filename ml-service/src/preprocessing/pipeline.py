from datetime import datetime, timezone

from src.schemas.reading import SensorReading

SIGNALS = ("heart_rate", "gsr", "temperature")


def normalize_timestamp(value: datetime) -> datetime:
    return value.astimezone(timezone.utc)


def reading_quality(reading: SensorReading) -> tuple[bool, list[str], list[str]]:
    missing = [signal for signal in SIGNALS if getattr(reading, signal) is None]
    reasons: list[str] = []
    if len(missing) == len(SIGNALS):
        reasons.append("all_signals_missing")
    if reading.signal_quality is not None and reading.signal_quality == 0:
        reasons.append("signal_quality_zero")
    return not reasons, missing, reasons
