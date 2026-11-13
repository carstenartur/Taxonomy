-- Historical sync_state rows have no captured branch and keep their original evidence.
create table workspace_branch_sync (
    scope_key varchar(64) primary key,
    username varchar(255) not null,
    repository_id varchar(255) not null,
    workspace_id varchar(255),
    sync_branch varchar(255) not null,
    last_synced_commit_id varchar(255),
    last_sync_timestamp timestamp(6),
    last_published_commit_id varchar(255),
    last_publish_timestamp timestamp(6),
    sync_status varchar(255) not null,
    unpublished_commit_count integer not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    constraint fk_ws_branch_sync_workspace foreign key (workspace_id) references user_workspace(workspace_id) on delete cascade
);
