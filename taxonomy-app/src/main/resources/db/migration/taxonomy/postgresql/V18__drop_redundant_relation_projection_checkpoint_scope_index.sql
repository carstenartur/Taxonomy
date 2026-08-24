-- The unique constraint uq_rel_projection_checkpoint_scope already owns an
-- equivalent unique index on (repository_id, workspace_scope_key, branch).
-- Drop the additional non-unique index introduced by V10 through one immutable
-- versioned migration. Existing installations that already ran the former
-- repeatable cleanup remain safe because the statement is idempotent.

drop index if exists idx_rel_projection_checkpoint_scope;
