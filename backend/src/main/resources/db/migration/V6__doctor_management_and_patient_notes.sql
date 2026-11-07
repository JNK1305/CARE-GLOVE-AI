-- V6: Doctor management and patient clinical notes schema enhancements

-- Add hospital, specialty, contact, employee_id to users
ALTER TABLE users ADD COLUMN hospital VARCHAR(120) NOT NULL DEFAULT 'CARE Hospital';
ALTER TABLE users ADD COLUMN specialty VARCHAR(120) NULL;
ALTER TABLE users ADD COLUMN contact VARCHAR(40) NULL;
ALTER TABLE users ADD COLUMN employee_id VARCHAR(64) NULL;
CREATE INDEX idx_user_hospital_role ON users (hospital, role);

-- Add hospital to patients
ALTER TABLE patients ADD COLUMN hospital VARCHAR(120) NOT NULL DEFAULT 'CARE Hospital';
CREATE INDEX idx_patient_hospital ON patients (hospital);

-- Add hospital, updated_at, is_internal to clinical_records
ALTER TABLE clinical_records ADD COLUMN hospital VARCHAR(120) NOT NULL DEFAULT 'CARE Hospital';
ALTER TABLE clinical_records ADD COLUMN updated_at DATETIME(6) NULL;
ALTER TABLE clinical_records ADD COLUMN is_internal BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_clinical_hospital_patient ON clinical_records (hospital, patient_id);

-- Populate initial specialties for existing demo doctors
UPDATE users SET specialty = 'Cardiology & Intensive Care' WHERE id = 'sharma';
UPDATE users SET specialty = 'Internal Medicine & Pulmonology' WHERE id = 'patil';
