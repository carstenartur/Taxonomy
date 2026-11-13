-- A stored nonblank workspace ID always denotes a real working-copy scope,
-- even when its spelling matches the legacy central display marker.
-- Only central keys move. Checkpoint IDs, attached vectors and business metadata stay intact.

alter table relation_decision_projection drop constraint if exists ck_rel_decision_workspace_scope;
update relation_decision_projection set workspace_scope_key = ' central' where workspace_id is null;
alter table relation_decision_projection add constraint ck_rel_decision_workspace_scope
    check ((workspace_id is null and workspace_scope_key = ' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

alter table relation_decision_projection_checkpoint drop constraint if exists ck_rel_projection_checkpoint_scope;
update relation_decision_projection_checkpoint set workspace_scope_key = ' central' where workspace_id is null;
alter table relation_decision_projection_checkpoint add constraint ck_rel_projection_checkpoint_scope
    check ((workspace_id is null and workspace_scope_key = ' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

alter table relation_projection_recovery drop constraint if exists ck_rel_projection_recovery_scope;
update relation_projection_recovery set workspace_scope_key = ' central' where workspace_id is null;
alter table relation_projection_recovery add constraint ck_rel_projection_recovery_scope
    check ((workspace_id is null and workspace_scope_key = ' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

alter table relation_decision_projection alter column workspace_scope_key set default ' central';

-- Existing Lucene documents already contain the optional owner field. Search selects that
-- field directly, so the SQL migration does not require an empty index or a mass rebuild.
update architecture_commit_index set workspace_scope_key = ' central' where workspace_id is null;
alter table architecture_commit_index add constraint ck_commit_index_workspace_scope
    check ((workspace_id is null and workspace_scope_key = ' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key = trim(workspace_id)));

-- The child has no independently chosen owner. Its established foreign key
-- proves which parent supplies the new storage key while retaining the audit.
alter table relation_evidence drop constraint fk_evidence_hypothesis_scope;
update relation_evidence set workspace_scope_key=' central'
    where hypothesis_id in (select id from relation_hypothesis where workspace_id is null);
update relation_hypothesis set workspace_scope_key=' central' where workspace_id is null;
alter table relation_evidence add constraint fk_evidence_hypothesis_scope
    foreign key (hypothesis_id, repository_id, workspace_scope_key, branch_name)
    references relation_hypothesis (id, repository_id, workspace_scope_key, branch_name);
alter table relation_hypothesis add constraint ck_hyp_owner_key
    check ((workspace_id is null and workspace_scope_key=' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key=trim(workspace_id)));
update relation_proposal set workspace_scope_key=' central' where workspace_id is null;
alter table relation_proposal add constraint ck_prop_owner_key
    check ((workspace_id is null and workspace_scope_key=' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key=trim(workspace_id)));
update taxonomy_relation set workspace_scope_key=' central' where workspace_id is null;
alter table taxonomy_relation add constraint ck_relation_owner_key
    check ((workspace_id is null and workspace_scope_key=' central')
        or (workspace_id is not null and trim(workspace_id) <> '' and workspace_scope_key=trim(workspace_id)));
