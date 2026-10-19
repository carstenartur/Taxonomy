-- Restore schema counterpart of PostgreSQL V41. Unknown legacy accounting stays null.
alter table analysis_continuation add column relation_calls_spent integer;
-- Preserve the original source grouping independently of a saved relation report.
alter table analysis_continuation add column relation_source_cohort_size integer;
