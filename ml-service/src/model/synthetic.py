import csv
import random
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from src.preprocessing.pipeline import SIGNALS

LABELS = ("NORMAL", "WARNING", "CRITICAL")
WINDOW_SIZE = 4
WINDOW_STRIDE = 2
SAMPLES_PER_RUN = 120
RUN_COUNT = 180
SEED = 20261013


@dataclass(frozen=True)
class Sample:
    run_id: str
    heart_rate: float
    gsr: float
    temperature: float
    label: str

    def features(self) -> tuple[float, float, float]:
        return self.heart_rate, self.gsr, self.temperature


def generate_runs(run_count: int = RUN_COUNT, seed: int = SEED) -> dict[str, list[Sample]]:
    if run_count < 5:
        raise ValueError("At least five independent runs are required for a grouped train/test split.")
    rng = random.Random(seed)
    runs: dict[str, list[Sample]] = {}
    for run_number in range(run_count):
        run_id = f"run-{run_number:04d}"
        baseline = (
            rng.uniform(62.0, 112.0),
            rng.uniform(0.2, 7.0),
            rng.uniform(35.8, 37.6),
        )
        heart_drift = rng.uniform(-0.025, 0.025)
        gsr_drift = rng.uniform(-0.002, 0.002)
        temperature_drift = rng.uniform(-0.0015, 0.0015)
        onset = rng.randint(24, 48)
        duration = rng.randint(20, 36)
        scenario = rng.choice(("heart_rate", "gsr", "temperature", "multi"))
        severity = rng.choice(("WARNING", "CRITICAL"))
        signal_scale = 1.0 if severity == "WARNING" else 1.7
        affected = (
            SIGNALS
            if scenario == "multi"
            else (scenario,)
        )
        rows: list[Sample] = []
        for index in range(SAMPLES_PER_RUN):
            in_event = onset <= index < onset + duration
            label = severity if in_event else "NORMAL"
            heart_rate = baseline[0] + heart_drift * index + rng.gauss(0, 2.0)
            gsr = baseline[1] + gsr_drift * index + rng.gauss(0, 0.25)
            temperature = baseline[2] + temperature_drift * index + rng.gauss(0, 0.08)
            if in_event:
                if "heart_rate" in affected:
                    heart_rate += rng.choice((-1.0, 1.0)) * rng.uniform(16.0, 26.0) * signal_scale
                if "gsr" in affected:
                    gsr += rng.uniform(2.0, 4.0) * signal_scale
                if "temperature" in affected:
                    temperature += rng.uniform(0.6, 1.0) * signal_scale
            rows.append(
                Sample(
                    run_id=run_id,
                    heart_rate=round(heart_rate, 4),
                    gsr=round(gsr, 4),
                    temperature=round(temperature, 4),
                    label=label,
                )
            )
        runs[run_id] = rows
    return runs


def windows(rows: list[Sample], window_size: int = WINDOW_SIZE, stride: int = WINDOW_STRIDE) -> Iterator[tuple[tuple[float, float, float], str]]:
    if window_size < 1 or stride < 1:
        raise ValueError("Window size and stride must be positive.")
    for start in range(0, len(rows) - window_size + 1, stride):
        window = rows[start : start + window_size]
        means = tuple(
            sum(getattr(sample, feature) for sample in window) / window_size
            for feature in SIGNALS
        )
        label = max(window, key=lambda sample: LABELS.index(sample.label)).label
        yield means, label


def write_dataset(runs: dict[str, list[Sample]], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(("run_id", *SIGNALS, "label"))
        for run_id, rows in runs.items():
            for features, label in windows(rows):
                writer.writerow((run_id, *features, label))
