# CARE-GLOVE AI Backend Integration Contract

Status: frontend/demo contract with a backend foundation now present. Sections A–G continue to describe the frontend's requested integration contract; the implemented backend is not yet connected to the frontend.

## A. Current frontend data sources

| Frontend data | Current source | Future backend API |
|---|---|---|
| Patient list and assignments | `patientsSeed` in `app/page.tsx` seeds `loadPatients`; `lib/services.ts` then uses `care-glove-patients` in `localStorage` as the primary store. Role access is filtered by `patientsVisibleToRole`. | `GET /api/patients` |
| Patient detail and demographics | The same `Patient` record in the app's patient state; demographics and admission fields are on `Patient`. | `GET /api/patients/{patientId}` |
| Admission / patient creation | `AdmissionModal` creates a `Patient`; `createPatient` validates bed/glove and writes the patient and timeline to local storage. IDs are currently generated in the UI. | `POST /api/patients` |
| Patient edits and discharge | `updatePatient` and `dischargePatient` mutate the same local patient record. | `PATCH /api/patients/{patientId}` |
| Current vitals and history | `DemoSensorAdapter` creates readings from the `Patient` seed/current fields and persists readings by patient ID in `care-glove-monitoring-history`; React holds latest readings/history in `MonitoringSnapshot`. | `GET /api/patients/{patientId}/vitals` |
| Alerts and vital status | `alertService.evaluate` in `lib/services.ts` evaluates demo readings on the frontend and writes `Alert` and `Notification` records to local storage. Patient `status` is Stable/Watch/Critical. This is demo rule logic, not ML. | `GET /api/patients/{patientId}/alerts`; backend owns alert evaluation/status for production. |
| Patient journey / timeline | `timelineService` and `lib/clinical.ts`; events are saved under `care-glove-clinical-timeline` and related clinical records under `care-glove-clinical-records`. | `GET /api/patients/{patientId}/journey` |
| Device, glove, ward and bed view | `DemoSensorAdapter`, `getGloves`, and static ward/bed definitions in `lib/services.ts`; glove inventory state is local. | `GET /api/wards`, `GET /api/wards/{wardId}/beds`, `GET /api/gloves` |
| Clinical notes, instructions and tests | `clinicalService` and the `ClinicalInstructions` component; records/tests are local-storage-backed and keyed by patient ID. | `GET /api/patients/{patientId}/clinical-records`; `POST /api/patients/{patientId}/clinical-records` |
| Notifications | `notificationService`; local `care-glove-notifications` records reference patient and alert IDs. Read state is local. | `GET /api/notifications`; `PATCH /api/notifications/{notificationId}` |
| Reports | `generateReport` assembles content in `app/page.tsx` from one patient ID, current reading, that patient's alerts and clinical records. `clinicalService.createReport` stores it locally; `exportReport` downloads text in the browser. | `GET /api/reports`; `POST /api/patients/{patientId}/reports`; `GET /api/reports/{reportId}` |
| User, session and role | `authService` uses hard-coded demo role/user/credential mappings and `care-glove-session` in local storage. The current roles are `admin`, `senior`, and `duty`. | `POST /api/auth/login`; `POST /api/auth/logout`; `GET /api/auth/me` |

No frontend `fetch`/HTTP API client currently exists. `lib/services.ts` and `lib/clinical.ts` directly perform local storage access; the UI calls these local/demo services.

## B. Domain entities

Use the existing types in `lib/domain.ts` and `lib/clinical.ts` as the initial wire model:

- **User / session:** `User { id, name, role }`; `Role` is `admin | senior | duty`. Demo session currently extends `User`.
- **Patient:** `Patient { id, name, age, ward, bed, bedId, glove, doctor, doctorId, status, bpm, temp, gsr, authorized, gender?, contact?, emergencyContact?, bloodGroup?, condition?, admissionDate?, admissionTime?, lifecycle?, monitoringStatus? }`. Admission data is currently part of this record; there is no separate admission type.
- **Ward / bed / glove:** `Ward { id, name }`, `Bed { id, wardId, label, gloveId }`, and `Glove { id, bedId, wardId, status, maintenanceNote? }`.
- **Monitoring:** `MonitoringReading { id, patientId, gloveId, parameter, value, timestamp, source }`; `MonitoringSample { id, patientId, gloveId, timestamp, bpm, temperature, gsr }`. The sample is the grouped/latest-reading form; history is parameter readings.
- **Alert / notification:** `Alert { id, patientId, gloveId, parameter, value, threshold, severity, timestamp, source, status }`; `Notification { id, alertId, patientId, severity, parameter, value, timestamp, read }`.
- **Clinical record / journey:** `ClinicalRecord { id, patientId, actorId, actorName, actorRole, type, content?, medicineName?, dosage?, frequency?, instruction?, testName?, result?, notes?, timestamp }`; `TimelineEvent { id, patientId, type, actor, description, timestamp, severity?, metadata? }`.
- **Report / test:** `Report { id, patientId, authorId, authorName, timestamp, title, content }`; `TestResult { id, patientId, authorId, authorName, timestamp, testName, result }`.

All related data is already associated through `patientId`; readings and alerts also include `gloveId`. The backend should preserve those identifiers and return the same patient identity throughout list, detail, monitoring, alert, clinical record, journey and report responses.

## C. Required API endpoints

All responses below use the existing domain types unless a wrapper is shown. JSON examples omit optional fields when not present. The API must authorize each request server-side; client role filtering is not an authorization boundary. The current backend foundation uses signed bearer JWTs passed through the authorization header; a secure HttpOnly cookie is a possible later frontend integration choice, not currently implemented.

### Authentication

#### `POST /api/auth/login`
- **Purpose:** Replace demo credential checks and create an authenticated session.
- **Request:** `{ "role": "admin" | "senior" | "duty", "email": "…", "password": "…" }`
- **Response:** `200 { "token": string, "user": { "id": string, "name": string, "role": Role } }`; `401` for invalid credentials.
- **Auth/role:** Public request; server validates the user and permitted role.

#### `POST /api/auth/logout`
- **Purpose:** End the current session.
- **Request:** Empty.
- **Response:** `204`; increments the user's token version so issued tokens are invalidated.
- **Auth/role:** Authenticated bearer token.

#### `GET /api/auth/me`
- **Purpose:** Restore the authenticated user on app load.
- **Request:** Empty.
- **Response:** `200 User`; `401` if there is no valid session.
- **Auth/role:** Authenticated bearer token.

### Patients

#### `GET /api/patients`
- **Purpose:** List patients visible to the current user.
- **Request:** Optional existing search/filter query parameters can be added when the UI needs server-side filtering; they are not required by the current behavior.
- **Response:** `200 Patient[]`.
- **Auth/role:** Admin sees all permitted records; senior sees patients assigned by `doctorId`; duty sees records allowed by `authorized` and patient lifecycle rules.

#### `GET /api/patients/{patientId}`
- **Purpose:** Load one patient profile.
- **Request:** Patient ID path parameter.
- **Response:** `200 Patient`; `404` if missing.
- **Auth/role:** Must pass the same patient authorization as the list.

#### `POST /api/patients`
- **Purpose:** Create an admission/patient record.
- **Request:** Existing `Patient` admission fields, excluding server-assigned `id`; preserve the existing ward/bed/glove and doctor assignment fields. Do not treat the demo initial vitals as a hardware reading.
- **Response:** `201 Patient` with canonical server-assigned `id`.
- **Auth/role:** Admin only, matching the current admission UI.

#### `PATCH /api/patients/{patientId}`
- **Purpose:** Update demographics, assignment, authorization, or lifecycle information.
- **Request:** `Partial<Patient>` containing only editable fields.
- **Response:** `200 Patient` with the updated record.
- **Auth/role:** Admin only in the current UI; server validates ward/bed/glove uniqueness and assignment consistency.

### Vitals

#### `GET /api/patients/{patientId}/vitals`
- **Purpose:** Load monitoring history for the selected patient.
- **Request:** Patient ID path parameter.
- **Response:** `200 MonitoringReading[]`, ordered by timestamp; each reading must have the requested `patientId` and assigned `gloveId`. The current/latest view can be derived by grouping the latest readings into the existing `MonitoringSample` shape.
- **Auth/role:** Same patient authorization as `GET /api/patients/{patientId}`.

The current frontend does not submit sensor readings to a server. Hardware/device ingestion is not a frontend endpoint in this contract. The implemented authenticated `POST /api/v1/vitals/ingest` device route requires `patientId`, `deviceId`, `timestamp`, `heartRate`, `gsr`, and `temperature`; source/quality/firmware metadata remains optional. It stores and forwards only the three sensor values.

### Alerts

#### `GET /api/patients/{patientId}/alerts`
- **Purpose:** Load alert history/status for the patient.
- **Request:** Patient ID path parameter.
- **Response:** `200 Alert[]` with existing `threshold`, `severity`, `status`, `source`, patient and glove identifiers.
- **Auth/role:** Same patient authorization.

No alert mutation endpoint is currently required by the UI; it evaluates/reads alerts but does not resolve them manually.

### Patient journey

#### `GET /api/patients/{patientId}/journey`
- **Purpose:** Load admission, assignment, monitoring, alert, clinical-record and discharge events for one patient.
- **Request:** Patient ID path parameter.
- **Response:** `200 TimelineEvent[]`, ordered by timestamp.
- **Auth/role:** Same patient authorization.

### Clinical records and notifications

#### `GET /api/patients/{patientId}/clinical-records`
- **Purpose:** Load patient notes, instructions, observations, reports and tests.
- **Request:** Patient ID path parameter.
- **Response:** `200 ClinicalRecord[]`.
- **Auth/role:** Same patient authorization; server enforces existing actor-role restrictions.

#### `POST /api/patients/{patientId}/clinical-records`
- **Purpose:** Persist a clinical record or test.
- **Request:** Existing `ClinicalRecord` fields excluding server-generated `id`, `patientId` (from path), actor identity (from session), and `timestamp` (server time). Existing clinical record type-specific fields remain unchanged.
- **Response:** `201 ClinicalRecord`.
- **Auth/role:** Authenticated; server enforces current `duty` and `senior` record-type restrictions.

#### `GET /api/notifications`
- **Purpose:** List notifications visible to the session.
- **Request:** Empty.
- **Response:** `200 Notification[]`.
- **Auth/role:** Authenticated; filter notifications through patient authorization.

#### `PATCH /api/notifications/{notificationId}`
- **Purpose:** Persist the current UI's notification-read action.
- **Request:** `{ "read": true }`
- **Response:** `200 Notification`.
- **Auth/role:** Authenticated; notification must belong to a visible patient.

### Reports

#### `GET /api/reports`
- **Purpose:** List reports visible to the current user.
- **Request:** Empty.
- **Response:** `200 Report[]`.
- **Auth/role:** Authenticated; filter by patient authorization.

#### `POST /api/patients/{patientId}/reports`
- **Purpose:** Generate and persist a report for the explicitly selected patient.
- **Request:** Empty; the patient identity comes from the path. The backend assembles report content from that patient's current record, vitals, alerts and clinical records rather than trusting patient data supplied by the browser.
- **Response:** `201 Report`.
- **Auth/role:** Same patient authorization; report author comes from the authenticated session.

#### `GET /api/reports/{reportId}`
- **Purpose:** Retrieve an existing report for viewing/downloading.
- **Request:** Report ID path parameter.
- **Response:** `200 Report`; `404` if missing.
- **Auth/role:** Authenticated; report patient must be visible to the user.

### Wards and devices

#### `GET /api/wards`
- **Purpose:** Replace the static ward list.
- **Request:** Empty.
- **Response:** `200 Ward[]`.
- **Auth/role:** Authenticated.

#### `GET /api/wards/{wardId}/beds`
- **Purpose:** Load bed assignments and availability for the existing ward/bed views.
- **Request:** Ward ID path parameter.
- **Response:** `200 Bed[]`.
- **Auth/role:** Authenticated; patient details in assignments must follow the caller's patient access.

#### `GET /api/gloves`
- **Purpose:** Load glove inventory, assignment and device status.
- **Request:** Empty.
- **Response:** `200 Glove[]`.
- **Auth/role:** Authenticated; restrict inventory/detail as required by existing application roles.

### ML

There is no current ML request/response model, prediction field, or confidence requirement in the frontend domain. No `/api/ml/predict` endpoint is part of the current required frontend contract. Define it with the backend/ML requirements before implementation; do not equate demo alert thresholds with an ML prediction or invent confidence fields.

## D. Database entities and relationships

These are the persisted concepts already represented by current domain types; they are not a schema prescription:

- **User:** `id`, `name`, `role`. Owns clinical records and reports.
- **Patient:** all fields listed in section B, including the current embedded admission demographics, lifecycle, and assignment fields. `doctorId` references a user; `doctor` is the existing display name.
- **Ward, Bed, Glove:** existing IDs, ward/bed relationship and glove assignment/status. A patient's `bedId` and `glove` point to the corresponding assignment.
- **MonitoringReading:** `id`, `patientId`, `gloveId`, `parameter`, `value`, `timestamp`, `source`; references patient and assigned glove.
- **Alert:** existing alert fields, references patient and glove.
- **Notification:** existing notification fields, references patient and alert.
- **ClinicalRecord:** existing content/type/actor fields, references patient and user.
- **TimelineEvent:** existing event fields, references patient; timeline entries can refer to related records through current metadata.
- **Report:** existing report fields, references patient and author.
- **TestResult:** existing test fields, references patient and author; currently also represented by a `ClinicalRecord` of type `TEST`.

In the existing frontend model, admission is embedded in `Patient` and ML has no defined persisted output. The backend foundation normalizes admissions and persists ML predictions internally, while returning patient-centric response DTOs at its API boundary.

## E. ML integration boundary

Current data flow is demo-only:

`DemoSensorAdapter` → `MonitoringSample` → frontend `alertService.evaluate` → `Alert` / patient `status` → UI.

In the target architecture, device ingestion and inference belong on the backend/ML side:

`Device / MonitoringSample` → backend → ML service → backend-owned risk/alert decision → persisted alert/status → existing frontend `Alert` and `Patient.status` views.

The frontend currently displays:

- **Prediction/risk:** no separate prediction object or ML score.
- **Vital status:** `Patient.status` (`Stable | Watch | Critical`) and `VitalRangeStatus` (`WITHIN_RANGE | BELOW_RANGE | ABOVE_RANGE | CRITICAL | UNKNOWN`).
- **Alert severity:** `WARNING | CRITICAL`; alert status is `OPEN | RESOLVED`.
- **Timestamp:** ISO timestamp strings on readings, alerts and timeline events.
- **Patient ID:** `patientId` on readings, alerts and events.
- **Confidence:** not present or required.

The numerical demo thresholds are implementation details in `lib/services.ts` and must not become an assumed ML contract.

## F. Frontend → Backend data flow

1. Authenticate and restore the session through `/api/auth/*`.
2. Request role-authorized patients; use the returned `Patient.id` for navigation and every subsequent patient request.
3. Fetch that patient's detail, vitals, alerts, journey and clinical records using the same ID.
4. Submit patient edits/clinical records through their patient-scoped endpoints.
5. Select a patient explicitly before report creation; send that ID in the report-generation path.
6. Fetch reports, ward/bed data, gloves and notifications through their corresponding service functions.

## G. Backend → ML → Frontend data flow

1. Device data enters the backend using a device-ingestion mechanism (not currently specified as a frontend API).
2. Backend passes patient-linked monitoring data to the ML service under a separately agreed ML contract.
3. ML returns a result to the backend; backend validates it and owns any decision to persist alert/status changes.
4. Backend exposes patient-linked monitoring readings, alerts and statuses through the APIs above.
5. Frontend renders the existing `MonitoringReading`, `Alert`, and `Patient.status` shapes, keyed by `patientId` and `gloveId`.

### Implemented Spring Boot ↔ FastAPI contract

- Spring sends `POST /api/v1/predict` with `user_id` set to the canonical patient ID, the same `patient_id`, ISO-8601 UTC `timestamp`, exactly three required sensor values (`heart_rate`, `gsr`, `temperature`), and device/source/receipt/quality/firmware metadata. The public ML service rejects missing/non-finite values and unknown sensor fields.
- FastAPI responds with `patient_id`, `timestamp`, `state` (`NORMAL`, `MONITOR`, or `POTENTIAL_ANOMALY`), nullable `anomaly_score`, `model_version`, `contributors`, `data_quality` (`valid`, `missing_signals`, `reasons`, `window_readings`), `persistence`, and `scored_window`.
- The backend validates response patient ID, timestamp, and state; backend/MySQL owns persistence counting, recovery, and alert lifecycle. FastAPI's process-local persistence object is not treated as durable backend state.
- Prediction storage keeps patient/reading association, returned timestamp/state/score/contributors and, following the response-contract fix, the data-quality details and scored-window flag. V2 adds those two prediction metadata columns without replacing existing predictions.
- V3 removes the obsolete SpO₂ columns from patient and sensor-reading storage; V1 remains unchanged as immutable migration history.
- Synthetic/manual sensor values are normalized before the same backend ingestion boundary that future hardware/Firebase adapters must use. Firebase and ESP32 are not implemented.

## Integration readiness and deferred work

- The existing service functions provide useful domain operations and naming, but their interfaces are synchronous and directly tied to browser local storage. Components call them synchronously and own some demo behavior (seed patients, monitoring lifecycle, alert evaluation, and report assembly). Replacing storage calls with HTTP is therefore **not** a drop-in implementation behind the current interfaces; reads/mutations must become async and React loading/error/state handling must be adapted.
- Patient IDs and demo user/role data are currently created/mapped in the frontend. Production identity and authorization must be server-owned.
- The `Patient` type currently combines profile, admission, assignment, current/demo vitals, and status. This contract preserves that shape for compatibility; normalize storage internally only if the API continues returning this existing response shape.
- No frontend preparation code was changed for this readiness check. The contract is documented first so the next implementation can introduce an async API service boundary without starting the backend or refactoring unrelated UI now.

## H. Items intentionally deferred

The Spring Boot/MySQL backend and independent FastAPI statistical ML service were exercised locally on 2026-10-03. MySQL 8.0.46 was reachable on port 3307; `careglove_db` was absent and was created as UTF-8/utf8mb4. Flyway V1 and V2 both succeeded, and a real backend startup validated the schema with Hibernate `ddl-auto: validate`. A second backend startup confirmed Flyway kept schema version 2 and the verification records remained available.

The real-MySQL synthetic API flow ingested 24 readings through Spring Boot; all 24 sensor rows and predictions were found in MySQL. The sequence produced `NORMAL`, `MONITOR`, and `POTENTIAL_ANOMALY` states, a single persisted alert, a recovery journey event, and the persisted alert transitions `OPEN` → `ACKNOWLEDGED` → `RESOLVED`. Duplicate reading submission returned 409. MySQL integrity queries found no orphaned sensor/prediction/alert relationships or duplicate patient/timestamp readings. The retained verification-only user/patient and synthetic clinical telemetry belong to the local backend database; no frontend demo records were read, changed, or copied.

FastAPI health/model-info, canonical prediction, invalid value, missing identity, malformed timestamp, duplicate, out-of-order, batch ordering, synthetic anomaly persistence/recovery, and process-restart state-reset behavior were also exercised. FastAPI scoring state is in memory and resets on restart; backend readings, predictions, alerts, and audit events remain in MySQL.

- Backend build/tests: PASS (`mvn -q verify`; H2 integration tests).
- MySQL authentication, Flyway V1/V2, backend startup/health, Hibernate validation, and real synthetic persistence flow: PASS.
- FastAPI tests: PASS (7 tests); live API and backend-to-ML calls: PASS.
- API authentication and role/assignment authorization: PASS in the MySQL-backed run.
- Frontend API client/mode switch, Firebase, ESP32, durable/shared ML state, and clinical validation: intentionally deferred. Frontend remains in demo mode.
