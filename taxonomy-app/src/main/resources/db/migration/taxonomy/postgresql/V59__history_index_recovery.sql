-- Keep the command independent of Git and of the rows that its projection can delete.
create table history_index_operation (
    operation_id varchar(64) primary key,
    command_kind varchar(12) not null,
    cause_id varchar(64) not null,
    actor_id varchar(64) not null,
    actor_scope varchar(255) not null,
    login_kind varchar(12) not null,
    binding_key varchar(64) not null,
    repository_id varchar(255) not null,
    workspace_id varchar(255),
    workspace_owner varchar(255),
    repository_scope varchar(24) not null,
    collaborative boolean not null,
    branch_name varchar(255) not null,
    captured_commit varchar(40),
    projection_source_key varchar(64),
    captured_generation varchar(36),
    projection_result varchar(2048),
    phase varchar(24) not null,
    attempts integer not null,
    result_count integer,
    executor_id varchar(64),
    lease_id varchar(36),
    created_at bigint not null,
    attempted_at bigint,
    due_at bigint,
    completed_at bigint,
    archived_at bigint,
    reason_code varchar(40)
);
create index ix_hist_index_due on history_index_operation(phase, due_at);
create index ix_hist_index_scope on history_index_operation(repository_id, workspace_id);
create table history_index_decision (
    decision_id varchar(36) primary key,
    operation_id varchar(64) not null,
    actor_id varchar(64) not null,
    decision_code varchar(40) not null,
    occurred_at bigint not null
);
create index ix_hist_decision_operation on history_index_decision(operation_id, occurred_at);
-- Purge captures immutable row identities before reporting a recoverable command.
create table history_index_purge_target (
    operation_id varchar(64) not null,
    projection_id bigint not null,
    primary key(operation_id, projection_id)
);
