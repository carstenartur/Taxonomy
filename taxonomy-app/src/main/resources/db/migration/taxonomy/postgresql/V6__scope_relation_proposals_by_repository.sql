-- Relation proposals were historically partitioned only by workspace_id.
-- Multi-repository deployments require repository_id as the mandatory first
-- tenant key; a null workspace denotes central proposal state only inside that
-- selected repository.

alter table relation_proposal
    add column repository_id varchar(255);

update relation_proposal
set workspace_id = null
where workspace_id is not null
  and btrim(workspace_id) = '';

do $$
declare
    unbound_workspace_proposal_count bigint;
    unbound_central_proposal_count bigint;
    primary_repository_count bigint;
begin
    update relation_proposal proposal
    set repository_id = workspace.source_repository_id
    from user_workspace workspace
    where proposal.repository_id is null
      and proposal.workspace_id is not null
      and workspace.workspace_id = proposal.workspace_id
      and workspace.source_repository_id is not null;

    select count(*)
    into unbound_workspace_proposal_count
    from relation_proposal
    where repository_id is null
      and workspace_id is not null;

    if unbound_workspace_proposal_count > 0 then
        raise exception
            'Cannot bind % existing workspace relation proposal(s): workspace source repository provenance is missing or ambiguous',
            unbound_workspace_proposal_count;
    end if;

    select count(*)
    into unbound_central_proposal_count
    from relation_proposal
    where repository_id is null
      and workspace_id is null;

    if unbound_central_proposal_count > 0 then
        select count(*)
        into primary_repository_count
        from system_repository
        where primary_repo = true;

        if primary_repository_count <> 1 then
            raise exception
                'Cannot bind % existing central relation proposal(s): expected exactly one primary repository, found %',
                unbound_central_proposal_count,
                primary_repository_count;
        end if;

        update relation_proposal
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

update relation_proposal
set workspace_scope_key = coalesce(workspace_id, '__shared__');

alter table relation_proposal
    alter column repository_id set not null,
    alter column workspace_scope_key set not null;

alter table relation_proposal
    drop constraint uk_relation_proposal_scope,
    add constraint fk_relation_proposal_repository
        foreign key (repository_id)
        references system_repository (repository_id),
    add constraint uk_relation_proposal_scope
        unique (
            repository_id,
            source_node_id,
            target_node_id,
            relation_type,
            workspace_scope_key);

drop index if exists idx_proposal_workspace;

create index idx_proposal_repository
    on relation_proposal (repository_id);

create index idx_proposal_repository_workspace
    on relation_proposal (repository_id, workspace_id);
