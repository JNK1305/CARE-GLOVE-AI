from datetime import datetime, timedelta, timezone

from src.schemas.reading import SensorReading


def stable_readings(
    patient_id: str,
    start: datetime,
    count: int,
    *,
    interval_seconds: int = 30,
) -> list[SensorReading]:
    start = start.astimezone(timezone.utc)
    return [
        SensorReading(
            user_id=patient_id,
            timestamp=start + timedelta(seconds=index * interval_seconds),
            heart_rate=75.0,
            gsr=0.42,
            temperature=36.7,
            source="SYNTHETIC",
        )
        for index in range(count)
    ]
