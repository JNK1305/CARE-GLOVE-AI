from dataclasses import dataclass
from datetime import datetime, timedelta

from src.config.settings import Settings
from src.schemas.reading import PersistenceResult


@dataclass
class PatientPersistence:
    active: bool = False
    started_at: datetime | None = None
    anomalous_window_count: int = 0
    last_alert_at: datetime | None = None
    cooldown_until: datetime | None = None
    recovered_at: datetime | None = None
    normal_window_count: int = 0


class PersistenceTracker:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._patients: dict[str, PatientPersistence] = {}

    def update(self, patient_id: str, timestamp: datetime, anomalous: bool) -> PersistenceResult:
        state = self._patients.setdefault(patient_id, PatientPersistence())
        alert_eligible = False
        if anomalous:
            state.normal_window_count = 0
            if not state.active:
                state.active = True
                state.started_at = timestamp
                state.anomalous_window_count = 0
                state.recovered_at = None
            state.anomalous_window_count += 1
            cooldown_active = state.cooldown_until is not None and timestamp < state.cooldown_until
            if state.anomalous_window_count >= self.settings.alert_persistence_windows and not cooldown_active:
                alert_eligible = True
                state.last_alert_at = timestamp
                state.cooldown_until = timestamp + timedelta(seconds=self.settings.alert_cooldown_seconds)
        else:
            state.normal_window_count += 1
            if state.active and state.normal_window_count >= self.settings.recovery_windows:
                state.active = False
                state.recovered_at = timestamp
                state.started_at = None
                state.anomalous_window_count = 0
                state.cooldown_until = None
                state.normal_window_count = 0
        duration = (timestamp - state.started_at).total_seconds() if state.started_at else 0.0
        return PersistenceResult(
            active=state.active,
            started_at=state.started_at,
            duration_seconds=max(0.0, duration),
            anomalous_window_count=state.anomalous_window_count,
            last_alert_at=state.last_alert_at,
            cooldown_until=state.cooldown_until,
            recovered_at=state.recovered_at,
            alert_eligible=alert_eligible,
        )

    def current(self, patient_id: str) -> PersistenceResult:
        state = self._patients.get(patient_id, PatientPersistence())
        return PersistenceResult(
            active=state.active,
            started_at=state.started_at,
            duration_seconds=0.0,
            anomalous_window_count=state.anomalous_window_count,
            last_alert_at=state.last_alert_at,
            cooldown_until=state.cooldown_until,
            recovered_at=state.recovered_at,
            alert_eligible=False,
        )
