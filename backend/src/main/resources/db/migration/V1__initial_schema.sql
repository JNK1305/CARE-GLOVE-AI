CREATE TABLE users (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    name VARCHAR(120) NOT NULL,
    role VARCHAR(16) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    token_version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT chk_user_role CHECK (role IN ('admin', 'senior', 'duty'))
);

CREATE TABLE wards (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    name VARCHAR(40) NOT NULL
);

CREATE TABLE beds (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    ward_id VARCHAR(40) NOT NULL,
    label VARCHAR(16) NOT NULL,
    glove_id VARCHAR(80) NOT NULL,
    CONSTRAINT uq_bed_ward_label UNIQUE (ward_id, label),
    CONSTRAINT uq_bed_glove UNIQUE (glove_id),
    CONSTRAINT fk_bed_ward FOREIGN KEY (ward_id) REFERENCES wards(id)
);

CREATE TABLE patients (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    age VARCHAR(32) NOT NULL,
    ward VARCHAR(40) NOT NULL,
    bed VARCHAR(16) NOT NULL,
    bed_id VARCHAR(80) NOT NULL,
    glove VARCHAR(80) NOT NULL,
    doctor_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    bpm DOUBLE NULL,
    spo2 DOUBLE NULL,
    temp DOUBLE NULL,
    gsr DOUBLE NULL,
    authorized BOOLEAN NOT NULL DEFAULT FALSE,
    gender VARCHAR(24) NULL,
    contact VARCHAR(40) NULL,
    emergency_contact VARCHAR(40) NULL,
    blood_group VARCHAR(8) NULL,
    clinical_condition VARCHAR(500) NULL,
    admission_date VARCHAR(32) NULL,
    admission_time VARCHAR(16) NULL,
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ADMITTED',
    monitoring_status VARCHAR(16) NOT NULL DEFAULT 'READY',
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_patient_doctor FOREIGN KEY (doctor_id) REFERENCES users(id),
    CONSTRAINT fk_patient_bed FOREIGN KEY (bed_id) REFERENCES beds(id),
    INDEX idx_patient_doctor_lifecycle (doctor_id, lifecycle)
);

CREATE TABLE admissions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    admission_date VARCHAR(32) NULL,
    admission_time VARCHAR(16) NULL,
    clinical_condition VARCHAR(500) NULL,
    lifecycle VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_admission_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    INDEX idx_admission_patient_created (patient_id, created_at)
);

CREATE TABLE devices (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NULL,
    ward VARCHAR(40) NOT NULL,
    bed VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    maintenance_note VARCHAR(500) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_device_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    INDEX idx_device_patient (patient_id)
);

CREATE TABLE monitoring_sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(80) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    persistence_started_at DATETIME(6) NULL,
    duration_seconds BIGINT NOT NULL DEFAULT 0,
    anomalous_window_count INT NOT NULL DEFAULT 0,
    normal_window_count INT NOT NULL DEFAULT 0,
    last_alert_at DATETIME(6) NULL,
    cooldown_until DATETIME(6) NULL,
    recovered_at DATETIME(6) NULL,
    CONSTRAINT fk_session_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    CONSTRAINT fk_session_device FOREIGN KEY (device_id) REFERENCES devices(id),
    INDEX idx_monitoring_patient_status (patient_id, status)
);

CREATE TABLE sensor_readings (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(80) NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    heart_rate DOUBLE NULL,
    spo2 DOUBLE NULL,
    gsr DOUBLE NULL,
    temperature DOUBLE NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    source VARCHAR(24) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    signal_quality DOUBLE NULL,
    firmware_version VARCHAR(64) NULL,
    CONSTRAINT uq_reading_patient_time UNIQUE (patient_id, timestamp),
    CONSTRAINT fk_reading_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    CONSTRAINT fk_reading_device FOREIGN KEY (device_id) REFERENCES devices(id),
    INDEX idx_reading_patient_time (patient_id, timestamp),
    INDEX idx_reading_device_time (device_id, timestamp)
);

CREATE TABLE ml_predictions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reading_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(64) NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    state VARCHAR(32) NOT NULL,
    anomaly_score DOUBLE NULL,
    model_version VARCHAR(64) NOT NULL,
    contributors JSON NOT NULL,
    data_quality_valid BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_prediction_reading UNIQUE (reading_id),
    CONSTRAINT fk_prediction_reading FOREIGN KEY (reading_id) REFERENCES sensor_readings(id),
    CONSTRAINT fk_prediction_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    INDEX idx_prediction_patient_time (patient_id, timestamp)
);

CREATE TABLE alerts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(80) NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    monitoring_state VARCHAR(32) NOT NULL,
    trigger_source VARCHAR(32) NOT NULL,
    source VARCHAR(64) NOT NULL,
    prediction_id VARCHAR(36) NOT NULL,
    parameter VARCHAR(32) NOT NULL,
    reading_value DOUBLE NOT NULL,
    threshold DOUBLE NULL,
    affected_signals JSON NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL,
    acknowledged_at DATETIME(6) NULL,
    acknowledged_by VARCHAR(64) NULL,
    resolved_at DATETIME(6) NULL,
    CONSTRAINT uq_alert_prediction UNIQUE (prediction_id),
    CONSTRAINT fk_alert_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    CONSTRAINT fk_alert_device FOREIGN KEY (device_id) REFERENCES devices(id),
    CONSTRAINT fk_alert_prediction FOREIGN KEY (prediction_id) REFERENCES ml_predictions(id),
    CONSTRAINT fk_alert_ack_user FOREIGN KEY (acknowledged_by) REFERENCES users(id),
    CONSTRAINT chk_alert_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    INDEX idx_alert_patient_time (patient_id, timestamp),
    INDEX idx_alert_status_created (status, created_at)
);

CREATE TABLE patient_journey_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    description VARCHAR(500) NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    severity VARCHAR(16) NULL,
    metadata JSON NULL,
    CONSTRAINT fk_journey_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    INDEX idx_journey_patient_time (patient_id, timestamp)
);

CREATE TABLE clinical_records (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_name VARCHAR(160) NOT NULL,
    actor_role VARCHAR(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    content TEXT NULL,
    medicine_name VARCHAR(160) NULL,
    dosage VARCHAR(120) NULL,
    frequency VARCHAR(120) NULL,
    instruction TEXT NULL,
    test_name VARCHAR(160) NULL,
    result TEXT NULL,
    notes TEXT NULL,
    timestamp DATETIME(6) NOT NULL,
    CONSTRAINT fk_clinical_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    CONSTRAINT fk_clinical_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    INDEX idx_clinical_patient_time (patient_id, timestamp)
);

CREATE TABLE reports (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    patient_id VARCHAR(64) NOT NULL,
    author_id VARCHAR(64) NOT NULL,
    author_name VARCHAR(160) NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    title VARCHAR(240) NOT NULL,
    content TEXT NOT NULL,
    CONSTRAINT fk_report_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    CONSTRAINT fk_report_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_report_patient_time (patient_id, timestamp)
);

CREATE TABLE notifications (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    alert_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    parameter VARCHAR(32) NOT NULL,
    reading_value DOUBLE NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_notification_alert UNIQUE (alert_id),
    CONSTRAINT fk_notification_alert FOREIGN KEY (alert_id) REFERENCES alerts(id),
    CONSTRAINT fk_notification_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
    INDEX idx_notification_patient_time (patient_id, timestamp)
);

CREATE TABLE audit_logs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    actor_id VARCHAR(64) NULL,
    action VARCHAR(40) NOT NULL,
    resource VARCHAR(40) NOT NULL,
    resource_id VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    metadata JSON NULL,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_audit_actor_time (actor_id, created_at),
    INDEX idx_audit_created (created_at)
);

INSERT INTO wards (id, name) VALUES
    ('ICU', 'ICU'),
    ('NICU', 'NICU'),
    ('Special Ward', 'Special Ward'),
    ('General Ward', 'General Ward');

INSERT INTO beds (id, ward_id, label, glove_id)
SELECT
    CONCAT(w.id, ':B-', LPAD(b.n, 2, '0')),
    w.id,
    CONCAT('B-', LPAD(b.n, 2, '0')),
    CONCAT('GLV-', w.glove_prefix, '-', LPAD(b.n, 3, '0'))
FROM (
    SELECT id, CASE id WHEN 'Special Ward' THEN 'SPL' WHEN 'General Ward' THEN 'GEN' ELSE id END AS glove_prefix
    FROM wards
) w
CROSS JOIN (
    SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
    UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
) b;

INSERT INTO devices (id, patient_id, ward, bed, status, maintenance_note, updated_at)
SELECT glove_id, NULL, ward_id, label, 'AVAILABLE', NULL, CURRENT_TIMESTAMP(6)
FROM beds;
