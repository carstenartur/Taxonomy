create table analysis_adapter_operation (
    id varchar(36) primary key,
    actor varchar(36) not null,
    repository_id varchar(320) not null,
    workspace_id varchar(320),
    branch_name varchar(512) not null,
    kind varchar(48) not null,
    source_commit varchar(64) not null,
    command_key varchar(64) not null,
    request_key varchar(64) not null,
    fingerprint varchar(64) not null,
    state varchar(16) not null,
    result_json clob,
    failure_code varchar(48),
    created_at bigint not null,
    updated_at bigint not null,
    row_version bigint not null,
    constraint uq_adapter_command unique (command_key)
);
create index idx_adapter_retention on analysis_adapter_operation(state,updated_at);
