-- Restore schema counterpart of PostgreSQL V40, after explanation-length V9.
alter table analysis_cluster_work add column progress_json clob;
