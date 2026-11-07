import json
from pathlib import Path

from src.model.training import train_model
from src.model.synthetic import SEED


def main() -> None:
    artifacts = Path(__file__).resolve().parents[2] / "artifacts"
    print(json.dumps(train_model(artifacts, seed=SEED), indent=2))


if __name__ == "__main__":
    main()
