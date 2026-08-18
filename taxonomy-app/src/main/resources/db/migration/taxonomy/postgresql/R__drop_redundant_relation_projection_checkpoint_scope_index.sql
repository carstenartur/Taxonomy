-- The unique constraint uq_rel_projection_checkpoint_scope already owns an
-- index over the exact same repository/workspace/branch columns. Retain the
-- constraint as the lookup and uniqueness authority and remove only the
-- duplicate non-unique index introduced by V10.

drop index if exists idx_rel_projection_checkpoint_scope;
