-- Bind portfolio roots to the exact logical repository, workspace/central scope and branch.
-- Central state is repository-owned. Ambiguous legacy per-user duplicates fail closed instead
-- of being merged or discarded implicitly.

alter table arch_project alter column scope_key type varchar(1024);
alter table solution_definition alter column scope_key type varchar(1024);
alter table product_catalog alter column scope_key type varchar(1024);

alter table arch_project
    add column repository_id varchar(255),
    add column workspace_scope varchar(320),
    add column branch_name varchar(255);
alter table solution_definition
    add column repository_id varchar(255),
    add column workspace_scope varchar(320),
    add column branch_name varchar(255);
alter table product_catalog
    add column repository_id varchar(255),
    add column workspace_scope varchar(320),
    add column branch_name varchar(255);

do $$
begin
    if (select count(*) from system_repository where primary_repo) <> 1 then
        raise exception 'Portfolio tenancy migration requires exactly one primary repository';
    end if;

    if exists (
        select 1
        from arch_project p
        left join user_workspace w on w.workspace_id = p.workspace_id
        where p.workspace_id is not null and w.id is null
    ) or exists (
        select 1
        from solution_definition s
        left join user_workspace w on w.workspace_id = s.workspace_id
        where s.workspace_id is not null and w.id is null
    ) or exists (
        select 1
        from product_catalog p
        left join user_workspace w on w.workspace_id = p.workspace_id
        where p.workspace_id is not null and w.id is null
    ) then
        raise exception 'Portfolio tenancy migration found an unknown workspace_id';
    end if;

    if exists (
        select 1
        from user_workspace w
        left join system_repository r on r.repository_id = w.source_repository_id
        where w.workspace_id in (
            select workspace_id from arch_project where workspace_id is not null
            union
            select workspace_id from solution_definition where workspace_id is not null
            union
            select workspace_id from product_catalog where workspace_id is not null
        )
        and (r.id is null or nullif(btrim(w.current_branch), '') is null)
    ) then
        raise exception 'Portfolio tenancy migration found incomplete workspace repository provenance';
    end if;

    if exists (
        select lower(btrim(project_key))
        from arch_project
        where workspace_id is null
        group by lower(btrim(project_key))
        having count(*) > 1
    ) then
        raise exception 'Portfolio tenancy migration found ambiguous central project keys';
    end if;
    if exists (
        select lower(btrim(solution_key))
        from solution_definition
        where workspace_id is null
        group by lower(btrim(solution_key))
        having count(*) > 1
    ) then
        raise exception 'Portfolio tenancy migration found ambiguous central solution keys';
    end if;
    if exists (
        select lower(btrim(product_key))
        from product_catalog
        where workspace_id is null
        group by lower(btrim(product_key))
        having count(*) > 1
    ) then
        raise exception 'Portfolio tenancy migration found ambiguous central product keys';
    end if;
end $$;

update arch_project p
set repository_id = btrim(coalesce(
        (select w.source_repository_id
         from user_workspace w
         where w.workspace_id = p.workspace_id),
        (select r.repository_id from system_repository r where r.primary_repo))),
    workspace_scope = case
        when p.workspace_id is not null then 'WORKSPACE:' || btrim(p.workspace_id)
        else 'CENTRAL'
    end,
    branch_name = btrim(coalesce(
        (select w.current_branch
         from user_workspace w
         where w.workspace_id = p.workspace_id),
        (select r.default_branch from system_repository r where r.primary_repo)));

update solution_definition s
set repository_id = btrim(coalesce(
        (select w.source_repository_id
         from user_workspace w
         where w.workspace_id = s.workspace_id),
        (select r.repository_id from system_repository r where r.primary_repo))),
    workspace_scope = case
        when s.workspace_id is not null then 'WORKSPACE:' || btrim(s.workspace_id)
        else 'CENTRAL'
    end,
    branch_name = btrim(coalesce(
        (select w.current_branch
         from user_workspace w
         where w.workspace_id = s.workspace_id),
        (select r.default_branch from system_repository r where r.primary_repo)));

update product_catalog p
set repository_id = btrim(coalesce(
        (select w.source_repository_id
         from user_workspace w
         where w.workspace_id = p.workspace_id),
        (select r.repository_id from system_repository r where r.primary_repo))),
    workspace_scope = case
        when p.workspace_id is not null then 'WORKSPACE:' || btrim(p.workspace_id)
        else 'CENTRAL'
    end,
    branch_name = btrim(coalesce(
        (select w.current_branch
         from user_workspace w
         where w.workspace_id = p.workspace_id),
        (select r.default_branch from system_repository r where r.primary_repo)));

update arch_project
set scope_key = 'v2|r' || char_length(repository_id)::text || ':' || repository_id
        || '|s' || char_length(workspace_scope)::text || ':' || workspace_scope
        || '|b' || char_length(branch_name)::text || ':' || branch_name;
update solution_definition
set scope_key = 'v2|r' || char_length(repository_id)::text || ':' || repository_id
        || '|s' || char_length(workspace_scope)::text || ':' || workspace_scope
        || '|b' || char_length(branch_name)::text || ':' || branch_name;
update product_catalog
set scope_key = 'v2|r' || char_length(repository_id)::text || ':' || repository_id
        || '|s' || char_length(workspace_scope)::text || ':' || workspace_scope
        || '|b' || char_length(branch_name)::text || ':' || branch_name;

alter table arch_project
    alter column repository_id set not null,
    alter column workspace_scope set not null,
    alter column branch_name set not null,
    add constraint fk_proj_repository foreign key (repository_id)
        references system_repository (repository_id),
    add constraint ck_proj_workspace_scope check (
        (workspace_id is null and workspace_scope = 'CENTRAL')
        or (workspace_id is not null and workspace_scope = 'WORKSPACE:' || btrim(workspace_id))
    ),
    add constraint ck_proj_scope_key_v2 check (scope_key like 'v2|r%');

alter table solution_definition
    alter column repository_id set not null,
    alter column workspace_scope set not null,
    alter column branch_name set not null,
    add constraint fk_sol_repository foreign key (repository_id)
        references system_repository (repository_id),
    add constraint ck_sol_workspace_scope check (
        (workspace_id is null and workspace_scope = 'CENTRAL')
        or (workspace_id is not null and workspace_scope = 'WORKSPACE:' || btrim(workspace_id))
    ),
    add constraint ck_sol_scope_key_v2 check (scope_key like 'v2|r%');

alter table product_catalog
    alter column repository_id set not null,
    alter column workspace_scope set not null,
    alter column branch_name set not null,
    add constraint fk_prod_repository foreign key (repository_id)
        references system_repository (repository_id),
    add constraint ck_prod_workspace_scope check (
        (workspace_id is null and workspace_scope = 'CENTRAL')
        or (workspace_id is not null and workspace_scope = 'WORKSPACE:' || btrim(workspace_id))
    ),
    add constraint ck_prod_scope_key_v2 check (scope_key like 'v2|r%');

create index idx_proj_tenant on arch_project (repository_id, workspace_scope, branch_name);
create index idx_sol_tenant on solution_definition (repository_id, workspace_scope, branch_name);
create index idx_prod_tenant on product_catalog (repository_id, workspace_scope, branch_name);
