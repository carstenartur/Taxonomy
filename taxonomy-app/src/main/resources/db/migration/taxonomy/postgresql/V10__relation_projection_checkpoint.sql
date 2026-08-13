-- A relation decision projection becomes readable only after one complete branch
-- rebuild has atomically replaced every row and recorded the exact Git head.

create table relation_decision_projection_checkpoint (
    id bigserial primary key,
    repository_id varchar(255) not null,
    workspace_id varchar(255),
    workspace_scope_key varchar(255) not null,
    branch varchar(255) not null,
    authoritative_commit_id varchar(40) not null,
    relation_count integer not null,
    completed_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint fk_rel_projection_checkpoint_repository
        foreign key (repository_id)
        references system_repository (repository_id),
    constraint uq_rel_projection_checkpoint_scope
        unique (repository_id, workspace_scope_key, branch),
    constraint ck_rel_projection_checkpoint_scope
        check (
            (workspace_id is null and workspace_scope_key = '__shared__')
            or
            (workspace_id is not null and workspace_scope_key = workspace_id)
        ),
    constraint ck_rel_projection_checkpoint_count
        check (relation_count >= 0)
);

create index idx_rel_projection_checkpoint_repository
    on relation_decision_projection_checkpoint (repository_id);

create index idx_rel_projection_checkpoint_scope
    on relation_decision_projection_checkpoint (
        repository_id,
        workspace_scope_key,
        branch
    );
