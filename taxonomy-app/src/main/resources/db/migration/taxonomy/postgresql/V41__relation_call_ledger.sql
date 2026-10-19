-- Actual relation calls are retained independently of retries and replayed grants.
-- Legacy rows initialize their nullable counter from the saved report on their next claim.
ALTER TABLE analysis_continuation ADD COLUMN relation_calls_spent INTEGER;
-- Source grouping is captured before the first response and retained across budget changes.
ALTER TABLE analysis_continuation ADD COLUMN relation_source_cohort_size INTEGER;
