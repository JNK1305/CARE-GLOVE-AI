import json
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier, ExtraTreesClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from src.model.synthetic import LABELS, SEED, generate_runs, windows, write_dataset
from src.preprocessing.pipeline import SIGNALS


def train_model(output_dir: Path, *, run_count: int = 180, seed: int = SEED) -> dict[str, Any]:
    runs = generate_runs(run_count, seed)
    run_ids = np.array(list(runs))
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=seed)
    train_indices, test_indices = next(splitter.split(run_ids, groups=run_ids))
    train_runs = {run_ids[index] for index in train_indices}
    test_runs = {run_ids[index] for index in test_indices}
    if train_runs & test_runs:
        raise RuntimeError("Training and held-out run groups overlap.")

    write_dataset(runs, output_dir / "synthetic_sensor_windows.csv")
    train = [(features, label) for run_id in train_runs for features, label in windows(runs[run_id])]
    test = [(features, label) for run_id in test_runs for features, label in windows(runs[run_id])]
    x_train = np.asarray([row[0] for row in train], dtype=np.float64)
    y_train = np.asarray([row[1] for row in train])
    x_test = np.asarray([row[0] for row in test], dtype=np.float64)
    y_test = np.asarray([row[1] for row in test])

    candidates: dict[str, Pipeline] = {
        "StandardScaler + LogisticRegression": Pipeline(
            steps=[
                ("scaler", StandardScaler()),
                ("classifier", LogisticRegression(max_iter=1000, random_state=seed, class_weight="balanced")),
            ]
        ),
        "RandomForestClassifier": Pipeline(
            steps=[
                ("classifier", RandomForestClassifier(
                    n_estimators=200, max_depth=12, class_weight="balanced_subsample",
                    n_jobs=-1, random_state=seed,
                ))
            ]
        ),
        "ExtraTreesClassifier": Pipeline(
            steps=[
                ("classifier", ExtraTreesClassifier(
                    n_estimators=200, max_depth=12, class_weight="balanced",
                    n_jobs=-1, random_state=seed,
                ))
            ]
        ),
    }
    scores: dict[str, float] = {}
    fitted_candidates: dict[str, Pipeline] = {}
    for name, candidate in candidates.items():
        candidate.fit(x_train, y_train)
        fitted_candidates[name] = candidate
        scores[name] = float(f1_score(
            y_test, candidate.predict(x_test), labels=LABELS, average="macro", zero_division=0
        ))
    selected_name = max(scores, key=scores.get)
    model = fitted_candidates[selected_name]
    predictions = model.predict(x_test)
    metrics: dict[str, Any] = {
        "model": selected_name,
        "model_version": "synthetic-scenario-v1",
        "seed": seed,
        "feature_order": list(SIGNALS),
        "target_labels": list(LABELS),
        "split": {
            "method": "GroupShuffleSplit by simulation run, before overlapping windows are generated",
            "train_runs": len(train_runs),
            "test_runs": len(test_runs),
            "train_windows": len(train),
            "test_windows": len(test),
        },
        "held_out": {
            "accuracy": float(accuracy_score(y_test, predictions)),
            "macro_f1": float(f1_score(y_test, predictions, labels=LABELS, average="macro", zero_division=0)),
            "confusion_matrix_labels": list(LABELS),
            "confusion_matrix": confusion_matrix(y_test, predictions, labels=LABELS).tolist(),
            "classification_report": classification_report(
                y_test, predictions, labels=LABELS, output_dict=True, zero_division=0
            ),
        },
        "candidate_macro_f1": scores,
        "limitations": (
            "Evaluation uses generated simulation scenarios only; it does not estimate performance on "
            "real people, clinical outcomes, or hardware."
        ),
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(
        {
            "pipeline": model,
            "feature_order": tuple(SIGNALS),
            "labels": LABELS,
            "version": metrics["model_version"],
            "window_size": 4,
            "window_stride": 2,
        },
        output_dir / "sensor_scenario_model.joblib",
    )
    (output_dir / "evaluation.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    return metrics
