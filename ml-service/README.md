# CARE-GLOVE AI ML Service

Independent FastAPI monitoring service. It validates the three required sensor readings, constructs 60-second windows at a configurable 15-second stride, extracts ordered per-signal means, establishes patient-specific median/MAD baselines, and separately runs a serialized scenario classifier.

This is a research software foundation only. Its configurable statistical settings are not clinically validated. It does not diagnose disease, recommend treatment, or decide clinical action. Human staff remain responsible for interpretation.

## Run locally (PowerShell)

```powershell
cd ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m pytest
uvicorn src.main:app --host 127.0.0.1 --port 8001
```

OpenAPI is available at `/docs`; `GET /health` is independent of Spring Boot and MySQL.

## Canonical reading

`POST /api/v1/predict` accepts the canonical `user_id`, ISO-8601 `timestamp`, and exactly three required sensor values: `heart_rate`, `gsr`, and `temperature`, in that feature order. GSR retains the existing project's µS convention; it is not transformed into raw ADC counts. Optional `patient_id`, `device_id`, source, receipt time, quality and firmware metadata are carried as metadata only; Firebase is not imported or required.

`POST /api/v1/predict/batch` accepts `{ "readings": [...] }`. Batch readings are ordered by patient and timestamp before processing. Duplicate patient/timestamp readings are rejected with HTTP 409.

State is held in process memory for this foundation; it resets on service restart and is not a durable model/baseline store. Spring Boot/MySQL owns durable readings, prediction records, persistence policy and alerts. Deploy a single ML process per isolated environment until an external shared state/model store is deliberately designed.

The trained ExtraTrees candidate classifies generated NORMAL/WARNING/CRITICAL simulation scenarios; it is not a medical prediction and has not been validated on real sensor/person data. Run `python -m src.model.train` from this directory to regenerate the deterministic grouped synthetic dataset, compare candidate classifiers, evaluate a held-out set of entire runs, and write the model and metrics into `artifacts/`. The training split assigns complete runs before generating overlapping windows; the StandardScaler candidate is fitted only inside the training split.

`ML_STRIDE_SECONDS` configures the statistical scoring stride and must be between 10 and 20; the default is 15. Window duration, baseline size, scoring settings, persistence and recovery counts are environment-configurable engineering settings, not medically validated thresholds.

Configure Python dependencies with `python -m pip install -r requirements.txt`.
