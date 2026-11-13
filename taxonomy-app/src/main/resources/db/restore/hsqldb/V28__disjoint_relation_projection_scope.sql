-- A stored nonblank workspace ID always denotes a real working-copy scope,
-- even when its spelling matches the legacy central display marker.
-- Only central keys move. Checkpoint IDs, attached vectors and business metadata stay intact.

update relation_decision_projection set workspace_scope_key = '' where workspace_id is null;
alter table relation_decision_projection add constraint ck_rel_decision_workspace_scope
    check ((workspace_id is null and workspace_scope_key = '')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

update relation_decision_projection_checkpoint set workspace_scope_key = '' where workspace_id is null;
alter table relation_decision_projection_checkpoint add constraint ck_rel_projection_checkpoint_scope
    check ((workspace_id is null and workspace_scope_key = '')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

update relation_projection_recovery set workspace_scope_key = '' where workspace_id is null;
alter table relation_projection_recovery add constraint ck_rel_projection_recovery_scope
    check ((workspace_id is null and workspace_scope_key = '')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

alter table relation_decision_projection alter column workspace_scope_key set default '';
