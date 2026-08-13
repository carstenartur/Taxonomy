-- A Git-authoritative relation commit remains visible to operators even when
-- its rebuildable database projection cannot be updated immediately.

create table relation_projection_recovery (
    id bigserial primary key,
    repository_id varchar(255) not null,
    workspace_id varchar(255),
    workspace_scope_key varchar(255) not null,
    branch varchar(255) not null,
    previous_head_commit varchar(40),
    authoritative_commit_id varchar(40) not null,
    causation_id varchar(255) not null,
    status varchar(32) not null,
    attempt_count integer not null,
    failure_type varchar(255) not null,
    failure_message varchar(2000) not null,
    first_observed_at timestamp with time zone not null,
    last_observed_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    version bigint not null default 0,
    constraint fk_rel_projection_recovery_repository
        foreign key (repository_id)
        references system_repository (repository_id),
    constraint uq_rel_projection_recovery_authority
        unique (
            repository_id,
            workspace_scope_key,
            branch,
            authoritative_commit_id
        ),
    constraint ck_rel_projection_recovery_scope
        check (
            (workspace_id is null and workspace_scope_key = '__shared__')
            or
            (workspace_id is not null and workspace_scope_key = workspace_id)
        ),
    constraint ck_rel_projection_recovery_status
        check (status in ('PENDING', 'RECOVERED', 'SUPERSEDED')),
    constraint ck_rel_projection_recovery_attempts
        check (attempt_count >= 1),
    constraint ck_rel_projection_recovery_completion
        check (
            (status = 'PENDING' and completed_at is null)
            or
            (status <> 'PENDING' and completed_at is not null)
        )
);

create index idx_rel_projection_recovery_repository
    on relation_projection_recovery (repository_id);

create index idx_rel_projection_recovery_pending
    on relation_projection_recovery (
        repository_id,
        workspace_scope_key,
        branch,
        status,
        id
    );
