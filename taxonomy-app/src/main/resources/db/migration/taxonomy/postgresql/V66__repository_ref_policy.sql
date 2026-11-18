create table repository_ref_rule (
    id varchar(64) primary key,
    storage_name varchar(255) not null,
    repository_id varchar(255) not null,
    workspace_id varchar(255),
    branch_name varchar(255) not null,
    revision bigint not null,
    prevent_updates boolean not null,
    prevent_force boolean not null,
    prevent_delete boolean not null
);
