-- No reference is published by migration. Existing tracking refs continue to be authoritative.
create table workspace_fetch_operation (
    operation_id varchar(64) primary key,
    fingerprint varchar(64) not null,
    cause_id varchar(64) not null,
    actor_id varchar(64) not null,
    login_kind varchar(12) not null,
    binding_key varchar(64) not null,
    policy_code varchar(40) not null,
    actor_scope varchar(255) not null,
    repository_id varchar(255) not null,
    workspace_id varchar(255) not null,
    local_branch varchar(255) not null,
    source_branch varchar(255) not null,
    fork_point varchar(40),
    requested_commit varchar(40) not null,
    expected_commit varchar(40),
    observed_commit varchar(40),
    result_commit varchar(40),
    phase varchar(24) not null,
    attempts integer not null,
    created_at bigint not null,
    last_attempt_at bigint,
    next_attempt_at bigint,
    completed_at bigint,
    archived_at bigint,
    archived_by varchar(64),
    executor_id varchar(64),
    lease_id varchar(36),
    decision_code varchar(40)
);
create index ix_ws_fetch_recovery on workspace_fetch_operation(phase, next_attempt_at);
create index ix_ws_fetch_retention on workspace_fetch_operation(archived_at, completed_at);
