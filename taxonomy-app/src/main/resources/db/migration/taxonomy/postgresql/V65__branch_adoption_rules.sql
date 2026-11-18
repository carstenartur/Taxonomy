create table branch_adoption_rule (
    id varchar(64) primary key, repository_id varchar(255) not null, workspace_id varchar(255),
    target_branch varchar(255) not null, source_branch varchar(255) not null, actor_scope varchar(255) not null,
    principal_id varchar(64) not null, login_kind varchar(12) not null, binding_key varchar(64) not null,
    enabled boolean not null, revision bigint not null, next_check bigint not null,
    status varchar(24) not null, detail varchar(255), source_cursor varchar(40), source_commit varchar(40),
    source_tip varchar(40), target_before varchar(40), target_after varchar(40), prepared_commit varchar(40)
);
create index ix_branch_adoption_due on branch_adoption_rule(enabled, next_check);
create table branch_adoption_event (
    id varchar(36) primary key, rule_id varchar(64) not null references branch_adoption_rule(id),
    revision bigint not null, recorded_at bigint not null, actor_id varchar(64) not null,
    source_branch varchar(255) not null, source_commit varchar(40), target_before varchar(40),
    target_after varchar(40), status varchar(24) not null, detail varchar(255), unique(rule_id, revision)
);
