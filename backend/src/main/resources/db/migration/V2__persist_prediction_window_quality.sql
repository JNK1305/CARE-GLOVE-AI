ALTER TABLE ml_predictions
    ADD COLUMN scored_window BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN data_quality JSON NULL;

UPDATE ml_predictions
SET data_quality = JSON_OBJECT(
    'valid', JSON_EXTRACT(IF(data_quality_valid, 'true', 'false'), '$'),
    'missing_signals', JSON_ARRAY(),
    'reasons', JSON_ARRAY(),
    'window_readings', 0
)
WHERE data_quality IS NULL;

ALTER TABLE ml_predictions
    MODIFY COLUMN data_quality JSON NOT NULL;
