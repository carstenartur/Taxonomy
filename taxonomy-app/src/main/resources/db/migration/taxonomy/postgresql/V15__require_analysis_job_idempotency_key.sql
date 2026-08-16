-- Every analysis job needs a stable idempotency identity so scoped uniqueness
-- remains portable across PostgreSQL, Oracle and SQL Server. Existing jobs that
-- predate the mandatory key receive a deterministic value derived from their
-- immutable UUID; API projections continue to hide these automatic keys.

update req_analysis_job
set idempotency_key = 'auto:' || id
where idempotency_key is null
   or btrim(idempotency_key) = '';

alter table req_analysis_job
    alter column idempotency_key set not null;
