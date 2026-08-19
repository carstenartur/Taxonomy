-- Bind analysis jobs, worker items, immutable snapshots and their queryable
-- mappings to the exact repository/workspace/branch tenant introduced for
-- projects and requirements in V12/V13. Parent rows are the sole authority.

alter table req_analysis_job
    add column if not exists scope_key varchar(1024);
alter table req_analysis_item
    add column if not exists scope_key varchar(1024),
    add column if not exists project_id bigint;
alter table req_analysis_snapshot
    add column if not exists scope_key varchar(1024);
alter table req_element_mapping
    add column if not exists scope_key varchar(1024);
alter table req_relation_mapping
    add column if not exists scope_key varchar(1024);

do $$
begin
    if exists (
        select 1
        from req_analysis_job job
        left join arch_project project on project.id = job.project_id
        where project.id is null
           or nullif(btrim(project.scope_key), '') is null
           or (job.scope_key is not null
               and btrim(job.scope_key) <> btrim(project.scope_key))
    ) then
        raise exception 'Analysis tenancy migration found a job without its exact project tenant';
    end if;

    -- The newly added job/item columns are not authoritative before backfill.
    -- Derive the expected tenant through job -> project and compare every other
    -- parent directly with that already-scoped project.
    if exists (
        select 1
        from req_analysis_item item
        left join req_analysis_job job on job.id = item.job_id
        left join arch_project project on project.id = job.project_id
        left join project_requirement requirement
            on requirement.id = item.requirement_id
        left join project_req_version version
            on version.id = item.requirement_version_id
        where job.id is null
           or project.id is null
           or requirement.id is null
           or version.id is null
           or nullif(btrim(project.scope_key), '') is null
           or job.project_id <> requirement.project_id
           or version.requirement_id <> requirement.id
           or btrim(requirement.scope_key) <> btrim(project.scope_key)
           or btrim(version.scope_key) <> btrim(project.scope_key)
           or (job.scope_key is not null
               and btrim(job.scope_key) <> btrim(project.scope_key))
           or (item.project_id is not null
               and item.project_id <> project.id)
           or (item.scope_key is not null
               and btrim(item.scope_key) <> btrim(project.scope_key))
    ) then
        raise exception 'Analysis tenancy migration found an item with inconsistent job/requirement/version authority';
    end if;

    if exists (
        select 1
        from req_analysis_snapshot snapshot
        left join arch_project project on project.id = snapshot.project_id
        left join project_requirement requirement
            on requirement.id = snapshot.requirement_id
        left join project_req_version version
            on version.id = snapshot.requirement_version_id
        left join req_analysis_job job on job.id = snapshot.job_id
        where project.id is null
           or requirement.id is null
           or version.id is null
           or job.id is null
           or nullif(btrim(project.scope_key), '') is null
           or requirement.project_id <> project.id
           or job.project_id <> project.id
           or version.requirement_id <> requirement.id
           or btrim(requirement.scope_key) <> btrim(project.scope_key)
           or btrim(version.scope_key) <> btrim(project.scope_key)
           or (job.scope_key is not null
               and btrim(job.scope_key) <> btrim(project.scope_key))
           or (snapshot.scope_key is not null
               and btrim(snapshot.scope_key) <> btrim(project.scope_key))
    ) then
        raise exception 'Analysis tenancy migration found a snapshot with inconsistent project/job/requirement/version authority';
    end if;

    if exists (
        select 1
        from req_element_mapping mapping
        left join req_analysis_snapshot snapshot on snapshot.id = mapping.snapshot_id
        left join arch_project project on project.id = snapshot.project_id
        where snapshot.id is null
           or project.id is null
           or nullif(btrim(project.scope_key), '') is null
           or (snapshot.scope_key is not null
               and btrim(snapshot.scope_key) <> btrim(project.scope_key))
           or (mapping.scope_key is not null
               and btrim(mapping.scope_key) <> btrim(project.scope_key))
    ) then
        raise exception 'Analysis tenancy migration found an element mapping without its exact snapshot tenant';
    end if;

    if exists (
        select 1
        from req_relation_mapping mapping
        left join req_analysis_snapshot snapshot on snapshot.id = mapping.snapshot_id
        left join arch_project project on project.id = snapshot.project_id
        where snapshot.id is null
           or project.id is null
           or nullif(btrim(project.scope_key), '') is null
           or (snapshot.scope_key is not null
               and btrim(snapshot.scope_key) <> btrim(project.scope_key))
           or (mapping.scope_key is not null
               and btrim(mapping.scope_key) <> btrim(project.scope_key))
    ) then
        raise exception 'Analysis tenancy migration found a relation mapping without its exact snapshot tenant';
    end if;

    if exists (
        select 1
        from project_requirement requirement
        left join req_analysis_snapshot snapshot
            on snapshot.id = requirement.current_snapshot_id
        left join arch_project project on project.id = snapshot.project_id
        where requirement.current_snapshot_id is not null
          and (snapshot.id is null
               or project.id is null
               or snapshot.requirement_id <> requirement.id
               or snapshot.project_id <> requirement.project_id
               or btrim(requirement.scope_key) <> btrim(project.scope_key)
               or (snapshot.scope_key is not null
                   and btrim(snapshot.scope_key) <> btrim(project.scope_key)))
    ) then
        raise exception 'Analysis tenancy migration found a current snapshot outside its requirement tenant';
    end if;

    if exists (
        select 1
        from req_analysis_item item
        left join req_analysis_job job on job.id = item.job_id
        left join req_analysis_snapshot snapshot on snapshot.id = item.snapshot_id
        left join arch_project project on project.id = job.project_id
        where item.snapshot_id is not null
          and (job.id is null
               or snapshot.id is null
               or project.id is null
               or snapshot.job_id <> item.job_id
               or snapshot.requirement_id <> item.requirement_id
               or snapshot.requirement_version_id <> item.requirement_version_id
               or snapshot.project_id <> job.project_id
               or (item.project_id is not null
                   and item.project_id <> job.project_id)
               or (snapshot.scope_key is not null
                   and btrim(snapshot.scope_key) <> btrim(project.scope_key))
               or (item.scope_key is not null
                   and btrim(item.scope_key) <> btrim(project.scope_key)))
    ) then
        raise exception 'Analysis tenancy migration found an item snapshot pointer outside its exact work identity';
    end if;
end $$;

update req_analysis_job job
set scope_key = project.scope_key
from arch_project project
where project.id = job.project_id;

update req_analysis_item item
set scope_key = job.scope_key,
    project_id = job.project_id
from req_analysis_job job
where job.id = item.job_id;

update req_analysis_snapshot snapshot
set scope_key = project.scope_key
from arch_project project
where project.id = snapshot.project_id;

update req_element_mapping mapping
set scope_key = snapshot.scope_key
from req_analysis_snapshot snapshot
where snapshot.id = mapping.snapshot_id;

update req_relation_mapping mapping
set scope_key = snapshot.scope_key
from req_analysis_snapshot snapshot
where snapshot.id = mapping.snapshot_id;

-- Exact parent keys used by the composite foreign keys below.
alter table project_requirement
    drop constraint if exists uq_req_id_proj_scope;
alter table project_requirement
    add constraint uq_req_id_proj_scope unique (id, project_id, scope_key);

alter table req_analysis_job
    drop constraint if exists uq_job_idempotency,
    drop constraint if exists uq_job_id_scope,
    drop constraint if exists uq_job_id_proj_scope,
    drop constraint if exists req_analysis_job_project_id_fkey,
    drop constraint if exists fk_job_project_scope;
alter table req_analysis_job
    alter column scope_key set not null,
    add constraint uq_job_idempotency
        unique (scope_key, project_id, idempotency_key),
    add constraint uq_job_id_scope unique (id, scope_key),
    add constraint uq_job_id_proj_scope unique (id, project_id, scope_key),
    add constraint fk_job_project_scope
        foreign key (project_id, scope_key)
        references arch_project (id, scope_key);

alter table req_analysis_item
    drop constraint if exists uq_item_job_req,
    drop constraint if exists uq_item_id_scope,
    drop constraint if exists req_analysis_item_job_id_fkey,
    drop constraint if exists req_analysis_item_requirement_id_fkey,
    drop constraint if exists req_analysis_item_requirement_version_id_fkey,
    drop constraint if exists fk_item_job_scope,
    drop constraint if exists fk_item_req_scope,
    drop constraint if exists fk_item_ver_scope,
    drop constraint if exists fk_item_snapshot_scope;
alter table req_analysis_item
    alter column scope_key set not null,
    alter column project_id set not null,
    add constraint uq_item_job_req
        unique (scope_key, job_id, requirement_id),
    add constraint uq_item_id_scope unique (id, scope_key),
    add constraint fk_item_job_scope
        foreign key (job_id, project_id, scope_key)
        references req_analysis_job (id, project_id, scope_key),
    add constraint fk_item_req_scope
        foreign key (requirement_id, project_id, scope_key)
        references project_requirement (id, project_id, scope_key),
    add constraint fk_item_ver_scope
        foreign key (requirement_version_id, requirement_id, scope_key)
        references project_req_version (id, requirement_id, scope_key);

alter table req_analysis_snapshot
    drop constraint if exists uq_snap_id_scope,
    drop constraint if exists uq_snap_req_proj_scope,
    drop constraint if exists uq_snap_item_scope,
    drop constraint if exists req_analysis_snapshot_project_id_fkey,
    drop constraint if exists req_analysis_snapshot_requirement_id_fkey,
    drop constraint if exists req_analysis_snapshot_requirement_version_id_fkey,
    drop constraint if exists req_analysis_snapshot_job_id_fkey,
    drop constraint if exists fk_snap_project_scope,
    drop constraint if exists fk_snap_req_scope,
    drop constraint if exists fk_snap_ver_scope,
    drop constraint if exists fk_snap_job_scope;
alter table req_analysis_snapshot
    alter column scope_key set not null,
    add constraint uq_snap_id_scope unique (id, scope_key),
    add constraint uq_snap_req_proj_scope
        unique (id, requirement_id, project_id, scope_key),
    add constraint uq_snap_item_scope
        unique (id, job_id, requirement_id, requirement_version_id,
                project_id, scope_key),
    add constraint fk_snap_project_scope
        foreign key (project_id, scope_key)
        references arch_project (id, scope_key),
    add constraint fk_snap_req_scope
        foreign key (requirement_id, project_id, scope_key)
        references project_requirement (id, project_id, scope_key),
    add constraint fk_snap_ver_scope
        foreign key (requirement_version_id, requirement_id, scope_key)
        references project_req_version (id, requirement_id, scope_key),
    add constraint fk_snap_job_scope
        foreign key (job_id, project_id, scope_key)
        references req_analysis_job (id, project_id, scope_key);

alter table req_element_mapping
    drop constraint if exists uq_elmap_snap_node,
    drop constraint if exists uq_elmap_id_scope,
    drop constraint if exists req_element_mapping_snapshot_id_fkey,
    drop constraint if exists fk_elmap_snapshot_scope;
alter table req_element_mapping
    alter column scope_key set not null,
    add constraint uq_elmap_snap_node
        unique (scope_key, snapshot_id, node_code),
    add constraint uq_elmap_id_scope unique (id, scope_key),
    add constraint fk_elmap_snapshot_scope
        foreign key (snapshot_id, scope_key)
        references req_analysis_snapshot (id, scope_key);

alter table req_relation_mapping
    drop constraint if exists uq_relmap_signature,
    drop constraint if exists uq_relmap_id_scope,
    drop constraint if exists req_relation_mapping_snapshot_id_fkey,
    drop constraint if exists fk_relmap_snapshot_scope;
alter table req_relation_mapping
    alter column scope_key set not null,
    add constraint uq_relmap_signature
        unique (scope_key, snapshot_id, source_code, target_code,
                relation_type, relation_origin),
    add constraint uq_relmap_id_scope unique (id, scope_key),
    add constraint fk_relmap_snapshot_scope
        foreign key (snapshot_id, scope_key)
        references req_analysis_snapshot (id, scope_key);

-- Nullable pointers become exact-tenant references once populated.
alter table project_requirement
    drop constraint if exists fk_req_current_snapshot_scope;
alter table project_requirement
    add constraint fk_req_current_snapshot_scope
        foreign key (current_snapshot_id, id, project_id, scope_key)
        references req_analysis_snapshot
            (id, requirement_id, project_id, scope_key)
        deferrable initially deferred;

alter table req_analysis_item
    add constraint fk_item_snapshot_scope
        foreign key (snapshot_id, job_id, requirement_id,
                     requirement_version_id, project_id, scope_key)
        references req_analysis_snapshot
            (id, job_id, requirement_id, requirement_version_id,
             project_id, scope_key)
        deferrable initially deferred;

-- Replace the legacy indexes whose names would otherwise hide their old,
-- project-only definitions behind CREATE INDEX IF NOT EXISTS.
drop index if exists idx_job_project;
drop index if exists idx_job_status;
drop index if exists idx_item_job;
drop index if exists idx_item_req;
drop index if exists idx_snap_project;
drop index if exists idx_snap_req;
drop index if exists idx_snap_job;
drop index if exists idx_elmap_snapshot;
drop index if exists idx_elmap_node;
drop index if exists idx_elmap_action;
drop index if exists idx_relmap_snapshot;
drop index if exists idx_relmap_source;
drop index if exists idx_relmap_target;

create index idx_job_project
    on req_analysis_job (scope_key, project_id, created_at);
create index idx_job_status
    on req_analysis_job (scope_key, project_id, status);
create index if not exists idx_job_scope
    on req_analysis_job (scope_key);
create index idx_item_job
    on req_analysis_item (scope_key, project_id, job_id);
create index idx_item_req
    on req_analysis_item (scope_key, project_id, requirement_id);
create index if not exists idx_item_scope
    on req_analysis_item (scope_key);
create index idx_snap_project
    on req_analysis_snapshot (scope_key, project_id, created_at);
create index idx_snap_req
    on req_analysis_snapshot (scope_key, requirement_id, created_at);
create index idx_snap_job
    on req_analysis_snapshot (scope_key, project_id, job_id);
create index if not exists idx_snap_scope
    on req_analysis_snapshot (scope_key);
create index idx_elmap_snapshot
    on req_element_mapping (scope_key, snapshot_id);
create index idx_elmap_node
    on req_element_mapping (scope_key, node_code);
create index idx_elmap_action
    on req_element_mapping (scope_key, action_status);
create index if not exists idx_elmap_scope
    on req_element_mapping (scope_key);
create index idx_relmap_snapshot
    on req_relation_mapping (scope_key, snapshot_id);
create index idx_relmap_source
    on req_relation_mapping (scope_key, source_code);
create index idx_relmap_target
    on req_relation_mapping (scope_key, target_code);
create index if not exists idx_relmap_scope
    on req_relation_mapping (scope_key);
