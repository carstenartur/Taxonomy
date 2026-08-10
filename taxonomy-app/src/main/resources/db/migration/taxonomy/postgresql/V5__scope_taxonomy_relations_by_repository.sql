-- Taxonomy relations were historically partitioned only by workspace_id.
-- Once multiple central repositories share one database, repository_id is the
-- mandatory first tenant key and a null workspace means central state only
-- inside that repository.

alter table taxonomy_relation
    add column repository_id varchar(255);

update taxonomy_relation
set workspace_id = null
where workspace_id is not null
  and btrim(workspace_id) = '';

do $$
declare
    unbound_workspace_relation_count bigint;
    unbound_central_relation_count bigint;
    primary_repository_count bigint;
begin
    -- A workspace already records the exact central repository from which it
    -- was provisioned. Preserve that provenance instead of assigning every
    -- historic workspace row to the catalog primary repository.
    update taxonomy_relation relation
    set repository_id = workspace.source_repository_id
    from user_workspace workspace
    where relation.repository_id is null
      and relation.workspace_id is not null
      and workspace.workspace_id = relation.workspace_id
      and workspace.source_repository_id is not null;

    select count(*)
    into unbound_workspace_relation_count
    from taxonomy_relation
    where repository_id is null
      and workspace_id is not null;

    if unbound_workspace_relation_count > 0 then
        raise exception
            'Cannot bind % existing workspace taxonomy relation(s): workspace source repository provenance is missing or ambiguous',
            unbound_workspace_relation_count;
    end if;

    select count(*)
    into unbound_central_relation_count
    from taxonomy_relation
    where repository_id is null
      and workspace_id is null;

    if unbound_central_relation_count > 0 then
        select count(*)
        into primary_repository_count
        from system_repository
        where primary_repo = true;

        if primary_repository_count <> 1 then
            raise exception
                'Cannot bind % existing central taxonomy relation(s): expected exactly one primary repository, found %',
                unbound_central_relation_count,
                primary_repository_count;
        end if;

        update taxonomy_relation
        set repository_id = (
            select repository_id
            from system_repository
            where primary_repo = true
        )
        where repository_id is null
          and workspace_id is null;
    end if;
end
$$;

update taxonomy_relation
set workspace_scope_key = coalesce(workspace_id, '__shared__');

alter table taxonomy_relation
    alter column repository_id set not null,
    alter column workspace_scope_key set not null;

alter table taxonomy_relation
    drop constraint uk_taxonomy_relation_scope,
    add constraint fk_taxonomy_relation_repository
        foreign key (repository_id)
        references system_repository (repository_id),
    add constraint uk_taxonomy_relation_scope
        unique (
            repository_id,
            source_node_id,
            target_node_id,
            relation_type,
            workspace_scope_key);

drop index if exists idx_rel_workspace;

create index idx_rel_repository
    on taxonomy_relation (repository_id);

create index idx_rel_repository_workspace
    on taxonomy_relation (repository_id, workspace_id);
