-- Private inactive control plane. No restored authority, credentials or application entity materialization.
create table restore_control_plan (
 plan_id varchar(36) primary key,
 actor_principal varchar(36) not null,
 plan_sha256 varchar(64) not null,
 plan_json clob not null,
 created_at varchar(40) not null,
 expires_at varchar(40) not null,
 approval_state varchar(24) not null,
 approved_sha256 varchar(64),
 row_version bigint not null
);
create index ix_restore_control_actor on restore_control_plan(actor_principal,plan_id);
create table restore_control_generation (
 target_database_sha256 varchar(64) primary key,
 generation_id varchar(36) not null unique,
 plan_id varchar(36) not null unique,
 constraint fk_control_generation_plan foreign key(plan_id) references restore_control_plan(plan_id)
);
