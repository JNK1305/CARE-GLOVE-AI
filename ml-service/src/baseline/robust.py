from collections.abc import Mapping, Sequence
from statistics import median

MAD_NORMALIZATION = 1.4826


def robust_deviations(
    current: Mapping[str, float],
    history: Sequence[Mapping[str, float]],
) -> tuple[float, list[dict[str, float | str]]]:
    contributors: list[dict[str, float | str]] = []
    squared_scores: list[float] = []
    for signal, value in current.items():
        baseline_values = [window[signal] for window in history if signal in window]
        if not baseline_values:
            continue
        center = median(baseline_values)
        mad = median([abs(item - center) for item in baseline_values])
        scale = MAD_NORMALIZATION * mad
        if scale == 0:
            scale = max(abs(center), 1.0) * 1e-6
        deviation = value - center
        score = abs(deviation) / scale
        squared_scores.append(score * score)
        contributors.append(
            {
                "signal": signal,
                "direction": "ABOVE_BASELINE" if deviation >= 0 else "BELOW_BASELINE",
                "current_value": value,
                "baseline_value": center,
                "deviation": deviation,
                "contribution": score,
            }
        )
    aggregate = (sum(squared_scores) / len(squared_scores)) ** 0.5 if squared_scores else 0.0
    contributors.sort(key=lambda item: float(item["contribution"]), reverse=True)
    return aggregate, contributors
