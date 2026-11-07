# CARE-GLOVE AI backend

Spring Boot REST and persistence foundation. The backend uses MySQL for durable clinical data and delegates statistical scoring to the independent FastAPI service. The existing Next.js demo mode remains the default; no frontend switch or demo-data deletion is performed by this backend setup.

## Local prerequisites

- Java 21
- Maven 3.9+
- MySQL 8-compatible server reachable at `localhost:3307` (or configure `DB_HOST` / `DB_PORT`)
- Python 3.10+ for the ML service

## Configure and run

Use the root `.env.example` as a variable reference and configure the values in the backend process environment, IDE launch configuration, or secret manager. Spring Boot does not load `.env` files automatically. Keep actual secrets out of source control. Use a random `JWT_SECRET` of at least 32 bytes. Create the MySQL database and configure a least-privilege account with schema migration privileges; Flyway creates the tables on startup. Do not use the example password as a real credential.

If both `ADMIN_BOOTSTRAP_EMAIL` and `ADMIN_BOOTSTRAP_PASSWORD` are configured, startup creates the initial admin only when no account with that email exists. The password must be at least 16 characters. Otherwise, provision an admin through the deployment's trusted bootstrap process; there is intentionally no public registration endpoint.

Start the services in separate terminals:

```powershell
cd ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn src.main:app --host 127.0.0.1 --port 8001
```

```powershell
cd backend
mvn spring-boot:run
```

The API listens on port 8080 by default. Health is available at `/api/health`; OpenAPI UI is at `/swagger-ui.html`. Authenticated endpoints require an `Authorization` header with a bearer access token. Configure comma-separated browser origins with `CORS_ALLOWED_ORIGINS`.

## API outline

- Auth: `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`
- User administration: `POST /api/users`
- Patients: `GET /api/patients`, `GET /api/patients/{patientId}`, `POST /api/patients`, `PATCH /api/patients/{patientId}`
- Patient data: `GET /api/patients/{patientId}/vitals`, `/alerts`, `/journey`, `/clinical-records`, `/monitoring-sessions`
- Ingestion: `POST /api/v1/vitals/ingest`; retry an accepted pending reading with `POST /api/v1/vitals/{readingId}/process`
- Alerts: `GET /api/alerts`, `GET /api/patients/{patientId}/alerts`, `PATCH /api/alerts/{alertId}`
- Reports: `POST /api/patients/{patientId}/reports` generates and stores a report for that patient; `GET /api/reports` and `GET /api/reports/{reportId}` read stored reports.
- Operational metadata: `GET /api/wards`, `/api/wards/{wardId}/beds`, `/api/gloves`
- ML service (service-to-service): `POST {ML_SERVICE_URL}/api/v1/predict`

All patient-specific reads/writes enforce server-side role and assignment checks. The exact JSON DTOs and permissions are represented by the controller request/response records and service authorization; the frontend integration contract remains in `BACKEND_INTEGRATION_CONTRACT.md`.

## Persistence and clinical-safety boundaries

Flyway owns the schema (`src/main/resources/db/migration`); Hibernate validates rather than creates/updates production schema. Patient, admission, user, device, sensor reading, prediction, monitoring session, alert, notification, journey, clinical record, report, and audit data are persisted. Ward, bed, and unassigned glove inventory is seeded from the existing static inventory, not as patient data.

The ML service provides configurable, non-clinically-validated statistical monitoring states only. It is not a diagnostic or treatment system and emits no clinical confidence score. The backend does not seed synthetic patients; a clearly labelled local verification patient and synthetic telemetry may be present in the development MySQL database after the live pipeline check. Firebase, physical device protocols, and frontend API mode switching remain future integration work. ML scoring state is process-local and resets on service restart; MySQL persistence is the backend's responsibility.

Sensor ingestion now requires `patientId`, `deviceId`, `timestamp`, `heartRate`, `gsr`, and `temperature`; quality/source/firmware metadata is optional. The pipeline forwards exactly those three sensor measurements to the ML service. Flyway V3 drops the obsolete SpO₂ columns from patient and reading tables, so back up any existing database before applying the migration if the old measurements must be retained outside the application.

## Verification

Run backend tests with:

```powershell
cd backend
mvn -q verify
```

Run ML service tests with:

```powershell
cd ml-service
python -m pytest -q
```

Spring integration tests use an isolated H2 database. On 2026-10-03, the backend was also started against local MySQL 8.0.46 on port 3307: Flyway V1/V2 succeeded, Hibernate validated the schema, the API health check reported both application and database up, and the real synthetic ingestion/prediction/alert/recovery flow was verified in MySQL. The local database retains labelled verification-only synthetic records. See `BACKEND_INTEGRATION_CONTRACT.md` for results. Never commit or publish local credentials.
