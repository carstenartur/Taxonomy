-- Fresh, isolated restore generations only. An older staged hypothesis has no
-- recorded branch authority: fail on its NULL value instead of inventing one.
alter table relation_hypothesis add column branch_name nvarchar(255);
alter table relation_evidence add column repository_id varchar(255);
alter table relation_evidence add column workspace_scope_key varchar(255);
alter table relation_evidence add column branch_name nvarchar(255);

alter table relation_hypothesis alter column branch_name set not null;
alter table relation_evidence alter column repository_id set not null;
alter table relation_evidence alter column workspace_scope_key set not null;
alter table relation_evidence alter column branch_name set not null;

alter table relation_hypothesis add constraint uq_hypothesis_parent_scope
    unique (id, repository_id, workspace_scope_key, branch_name);
alter table relation_hypothesis add constraint uq_hypothesis_branch_session_relation
    unique (repository_id, workspace_scope_key, branch_name, source_node_id,
        target_node_id, relation_type, analysis_session_scope_key);
alter table relation_hypothesis drop constraint uq_hypothesis_repository_workspace_session_relation;
alter table relation_evidence add constraint fk_evidence_hypothesis_scope
    foreign key (hypothesis_id, repository_id, workspace_scope_key, branch_name)
    references relation_hypothesis (id, repository_id, workspace_scope_key, branch_name);
create index idx_hyp_branch_scope on relation_hypothesis (repository_id, workspace_scope_key, branch_name);
