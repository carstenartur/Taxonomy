-- Every supported database schema needs a stable idempotency identity so scoped
-- uniqueness has equivalent semantics. This PostgreSQL migration assigns legacy
-- jobs a deterministic value derived from their immutable UUID before making the
-- key mandatory; API projections continue to hide these automatic keys.

update req_analysis_job
set idempotency_key = 'auto:' || id
where idempotency_key is null
   or btrim(idempotency_key) = '';

alter table req_analysis_job
    alter column idempotency_key set not null;
