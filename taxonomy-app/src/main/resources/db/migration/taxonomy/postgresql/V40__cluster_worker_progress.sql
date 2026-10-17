-- Fenced, bounded observations on the existing task authority. Depends on V39.
-- Mutable progress is separate from immutable task inputs and committed results.
ALTER TABLE analysis_cluster_work ADD COLUMN progress_json TEXT;
