-- Additive staging support for immutable based-file evidence; legacy exports remain unchanged.
alter table interop_operation add column file_export_json clob;
alter table interop_operation add column base_file_id varchar(36);
alter table interop_operation add column result_file_id varchar(36);

create table interop_file (
    id varchar(36) not null primary key,
    scope_id varchar(64) not null,
    connection_id varchar(36) not null,
    operation_id varchar(36) not null,
    actor varchar(160) not null,
    kind varchar(16) not null,
    profile varchar(64) not null,
    profile_version varchar(32) not null,
    binding_json clob not null,
    sha256 varchar(64) not null,
    byte_count bigint not null,
    media_type varchar(200) not null,
    filename varchar(240) not null,
    model_identifier varchar(2048),
    review_fingerprint varchar(64),
    losses_json clob not null,
    content blob not null,
    created_at varchar(40) not null
);
create index ix_interop_file_scope on interop_file(scope_id,connection_id,actor,kind);
