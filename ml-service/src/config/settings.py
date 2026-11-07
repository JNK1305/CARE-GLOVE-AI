from dataclasses import dataclass
import os


@dataclass(frozen=True)
class Settings:
    model_version: str = "mvp-1"
    window_seconds: int = 60
    stride_seconds: int = 15
    baseline_windows: int = 5
    monitor_z: float = 3.5
    anomaly_z: float = 6.0
    alert_persistence_windows: int = 3
    recovery_windows: int = 3
    alert_cooldown_seconds: int = 900
    max_history: int = 2000

    @classmethod
    def from_env(cls) -> "Settings":
        settings = cls(
            model_version=os.getenv("ML_MODEL_VERSION", "mvp-1"),
            window_seconds=int(os.getenv("ML_WINDOW_SECONDS", "60")),
            stride_seconds=int(os.getenv("ML_STRIDE_SECONDS", "15")),
            baseline_windows=int(os.getenv("ML_BASELINE_WINDOWS", "5")),
            monitor_z=float(os.getenv("ML_MONITOR_Z", "3.5")),
            anomaly_z=float(os.getenv("ML_ANOMALY_Z", "6.0")),
            alert_persistence_windows=int(os.getenv("ML_ALERT_PERSISTENCE_WINDOWS", "3")),
            recovery_windows=int(os.getenv("ML_RECOVERY_WINDOWS", "3")),
            alert_cooldown_seconds=int(os.getenv("ML_ALERT_COOLDOWN_SECONDS", "900")),
            max_history=int(os.getenv("ML_MAX_HISTORY", "2000")),
        )
        if (
            settings.window_seconds <= 0
            or not 10 <= settings.stride_seconds <= 20
            or settings.baseline_windows < 1
            or settings.monitor_z <= 0
            or settings.anomaly_z <= settings.monitor_z
            or settings.alert_persistence_windows < 1
            or settings.recovery_windows < 1
            or settings.alert_cooldown_seconds < 0
            or settings.max_history < 3
        ):
            raise ValueError("Invalid ML pipeline configuration.")
        return settings
