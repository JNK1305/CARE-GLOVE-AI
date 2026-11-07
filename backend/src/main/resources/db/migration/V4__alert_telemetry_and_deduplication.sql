ALTER TABLE alerts
    ADD COLUMN heart_rate DOUBLE NULL,
    ADD COLUMN gsr DOUBLE NULL,
    ADD COLUMN temperature DOUBLE NULL,
    ADD COLUMN model_version VARCHAR(64) NULL,
    ADD COLUMN model_score DOUBLE NULL,
    ADD COLUMN deduplication_key VARCHAR(160) NULL;

CREATE INDEX idx_alert_dedup_status ON alerts (deduplication_key, status);
