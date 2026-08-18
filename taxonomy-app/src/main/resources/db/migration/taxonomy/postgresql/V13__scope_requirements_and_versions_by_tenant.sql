-- Bind requirements and immutable requirement versions to the exact project tenant.
-- The migration derives authority only from already-scoped parent rows and fails
-- closed if pre-existing columns disagree with that authority.

alter table project_requirement
    add column if not exists scope_key varchar(1024);
alter table project_req_version
    add column if not exists scope_key varchar(1024);

do $$
begin
    if exists (
        select 1
        from project_requirement requirement
        left join arch_project project on project.id = requirement.project_id
        where project.id is null or nullif(btrim(project.scope_key), '') is null
    ) then
        raise exception 'Requirement tenancy migration found a requirement without an exact project tenant';
    end if;

    if exists (
        select 1
        from project_requirement requirement
        join arch_project project on project.id = requirement.project_id
        where requirement.scope_key is not null
          and btrim(requirement.scope_key) <> btrim(project.scope_key)
    ) then
        raise exception 'Requirement tenancy migration found a requirement/project scope mismatch';
    end if;

    -- project_requirement.scope_key was introduced immediately above and has not
    -- been backfilled yet. Existing versions therefore derive their pre-migration
    -- authority through requirement -> project, not through the new child column.
    if exists (
        select 1
        from project_req_version version
        left join project_requirement requirement
            on requirement.id = version.requirement_id
        left join arch_project project on project.id = requirement.project_id
        where requirement.id is null
           or project.id is null
           or nullif(btrim(project.scope_key), '') is null
    ) then
        raise exception 'Requirement tenancy migration found a version without an exact requirement tenant';
    end if;

    if exists (
        select 1
        from project_req_version version
        join project_requirement requirement
            on requirement.id = version.requirement_id
        join arch_project project on project.id = requirement.project_id
        where version.scope_key is not null
          and btrim(version.scope_key) <> btrim(project.scope_key)
    ) then
        raise exception 'Requirement tenancy migration found a version/requirement scope mismatch';
    end if;
end $$;

update project_requirement requirement
set scope_key = project.scope_key
from arch_project project
where project.id = requirement.project_id;

update project_req_version version
set scope_key = requirement.scope_key
from project_requirement requirement
where requirement.id = version.requirement_id;

alter table arch_project
    drop constraint if exists uq_proj_id_scope;
alter table arch_project
    add constraint uq_proj_id_scope unique (id, scope_key);

alter table project_requirement
    drop constraint if exists uq_req_project_key,
    drop constraint if exists uq_req_id_scope,
    drop constraint if exists fk_req_project_scope,
    drop constraint if exists fk_req_current_version_scope;
alter table project_requirement
    alter column scope_key set not null,
    add constraint uq_req_project_key
        unique (scope_key, project_id, requirement_key),
    add constraint uq_req_id_scope unique (id, scope_key),
    add constraint fk_req_project_scope
        foreign key (project_id, scope_key)
        references arch_project (id, scope_key);

alter table project_req_version
    drop constraint if exists uq_reqver_number,
    drop constraint if exists uq_reqver_hash,
    drop constraint if exists uq_reqver_id_scope,
    drop constraint if exists uq_reqver_id_req_scope,
    drop constraint if exists fk_reqver_requirement_scope;
alter table project_req_version
    alter column scope_key set not null,
    add constraint uq_reqver_number
        unique (scope_key, requirement_id, version_number),
    add constraint uq_reqver_hash
        unique (scope_key, requirement_id, content_hash),
    add constraint uq_reqver_id_scope unique (id, scope_key),
    add constraint uq_reqver_id_req_scope
        unique (id, requirement_id, scope_key),
    add constraint fk_reqver_requirement_scope
        foreign key (requirement_id, scope_key)
        references project_requirement (id, scope_key);

-- The pointer is nullable while a requirement is first created. Once populated,
-- it may only address a version belonging to that same requirement and tenant.
alter table project_requirement
    add constraint fk_req_current_version_scope
        foreign key (current_version_id, id, scope_key)
        references project_req_version (id, requirement_id, scope_key)
        deferrable initially deferred;

create index if not exists idx_req_scope
    on project_requirement (scope_key);
create index if not exists idx_req_status_scope
    on project_requirement (scope_key, project_id, status);
create index if not exists idx_reqver_scope
    on project_req_version (scope_key);
