-- Optional distributed authority. Existing local checkpoints remain valid.
-- Runtime grants are intentionally excluded from portable backup records.
ALTER TABLE analysis_continuation ADD COLUMN cluster_operation_id VARCHAR(160);
ALTER TABLE analysis_continuation ADD COLUMN cluster_source_json TEXT;
ALTER TABLE analysis_continuation ADD COLUMN cluster_catalogue_hash VARCHAR(64);
ALTER TABLE analysis_question_checkpoint ADD COLUMN attempt_owner VARCHAR(36);
ALTER TABLE analysis_question_checkpoint ADD COLUMN cluster_operation_id VARCHAR(160);
ALTER TABLE analysis_question_checkpoint ADD COLUMN cluster_task_id VARCHAR(640);
ALTER TABLE analysis_question_checkpoint ADD COLUMN cluster_delivery INTEGER;
