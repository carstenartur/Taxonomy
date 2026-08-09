-- Evolve the single system repository/workspace metadata into an explicit
-- multi-repository catalog while preserving the historic taxonomy-dsl storage.

alter table system_repository
    add column storage_repository_name varchar(255),
    add column slug varchar(255),
    add column description varchar(1000),
    add column visibility varchar(255),
    add column lifecycle_state varchar(255),
    add column provisioning_error varchar(2000),
    add column owner_type varchar(255),
    add column owner_id varchar(255),
    add column upstream_repository_id varchar(255),
    add column upstream_branch varchar(255),
    add column fork_point_commit varchar(255),
    add column created_by varchar(255),
    add column updated_at timestamp(6) with time zone,
    add column version bigint not null default 0;

update system_repository
set storage_repository_name = case
        when primary_repo then 'taxonomy-dsl'
        else 'central-' || repository_id
    end,
    slug = case
        when primary_repo then 'shared-architecture'
        else 'repository-' || lower(repository_id)
    end,
    visibility = case when primary_repo then 'ORGANIZATION' else 'PRIVATE' end,
    lifecycle_state = 'ACTIVE',
    provisioning_error = null,
    owner_type = case when primary_repo then 'SYSTEM' else 'USER' end,
    owner_id = coalesce(owner_id, 'system'),
    created_by = coalesce(created_by, 'system'),
    updated_at = coalesce(updated_at, created_at);

alter table system_repository
    add constraint uq_system_repository_storage unique (storage_repository_name),
    add constraint uq_system_repository_slug unique (slug);

alter table user_workspace
    add column source_branch varchar(255),
    add column relationship_type varchar(255),
    add column last_fetched_commit varchar(255),
    add column last_integrated_commit varchar(255);

update user_workspace
set source_branch = coalesce(source_branch, base_branch, sync_target_branch, 'draft'),
    relationship_type = coalesce(relationship_type, 'WORKING_COPY'),
    last_fetched_commit = coalesce(last_fetched_commit, base_commit),
    last_integrated_commit = coalesce(last_integrated_commit, base_commit);

create index idx_workspace_source_repository
    on user_workspace (source_repository_id);
